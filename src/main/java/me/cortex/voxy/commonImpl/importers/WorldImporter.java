package me.cortex.voxy.commonImpl.importers;

import com.mojang.serialization.Codec;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class WorldImporter implements IDataImporter {
    private final WorldEngine world;
    private final int minSectionY;
    private final int maxSectionY;
    private final PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;
    private final AtomicInteger estimatedTotalChunks = new AtomicInteger();//Slowly converges to the true value
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger chunksProcessed = new AtomicInteger();

    private final ConcurrentLinkedDeque<Runnable> jobQueue = new ConcurrentLinkedDeque<>();
    private final Service service;

    private volatile boolean isRunning;
    private final AtomicBoolean worldRefAcquired = new AtomicBoolean();
    private ZipFile zipSource;

    public WorldImporter(WorldEngine worldEngine, Level mcWorld, ServiceManager sm, BooleanSupplier runChecker) {
        this(worldEngine, mcWorld.registryAccess(), mcWorld.getMinY(), mcWorld.getHeight(), sm, runChecker);
    }

    WorldImporter(WorldEngine worldEngine, RegistryAccess registries, int minY, int height,
                  ServiceManager sm, BooleanSupplier runChecker) {
        this.world = worldEngine;
        this.minSectionY = Math.floorDiv(minY, 16);
        this.maxSectionY = Math.floorDiv(minY + height + 15, 16);
        this.service = sm.createService(()->new Pair<>(()->this.jobQueue.poll().run(), ()->{}), 3, "World importer", runChecker);

        var biomeRegistry = registries.lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
        this.defaultBiomeProvider = new PalettedContainerRO<>() {
            @Override
            public Holder<Biome> get(int x, int y, int z) {
                return defaultBiome;
            }

            @Override
            public void getAll(Consumer<Holder<Biome>> action) {
                action.accept(defaultBiome);
            }

            @Override
            public void write(FriendlyByteBuf buf) {

            }

            @Override
            public int getSerializedSize() {
                return 0;
            }

            @Override
            public int bitsPerEntry() {
                return 0;
            }

            @Override
            public boolean maybeHas(Predicate<Holder<Biome>> predicate) {
                return predicate.test(defaultBiome);
            }

            @Override
            public void forEachInPalette(Consumer<Holder<Biome>> consumer) {
                consumer.accept(defaultBiome);
            }

            @Override
            public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) {
                counter.accept(defaultBiome, 1);
            }

            @Override
            public PalettedContainer<Holder<Biome>> copy() {
                return null;
            }

            @Override
            public PalettedContainer<Holder<Biome>> recreate() {
                return null;
            }

            @Override
            public PackedData<Holder<Biome>> pack(Strategy<Holder<Biome>> provider) {
                return null;
            }
        };

        var factory = PalettedContainerFactory.create(registries);
        this.biomeCodec = factory.biomeContainerCodec();
        this.blockStateCodec = factory.blockStatesContainerCodec();
    }


    @Override
    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning || this.isShutdown.get()) {
            throw new IllegalStateException();
        }
        if (this.worker == null) {//Can happen if no files
            this.cleanup();
            completionCallback.onCompletion(0);
            return;
        }
        this.isRunning = true;
        this.world.acquireRef();
        this.worldRefAcquired.set(true);
        this.updateCallback = updateCallback;
        this.completionCallback = completionCallback;
        this.worker.start();
    }

    @Override
    public WorldEngine getEngine() {
        return this.world;
    }

    private final AtomicBoolean isShutdown = new AtomicBoolean();
    public void shutdown() {
        this.isRunning = false;
        var worker = this.worker;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        this.cleanup();
    }

    private void cleanup() {
        if (this.isShutdown.getAndSet(true)) return;
        if (this.service.isLive()) {
            this.service.shutdown();
        }
        //Free all the remaining entries by running the lambda
        while (!this.jobQueue.isEmpty()) {
            this.jobQueue.poll().run();
        }
        if (this.worldRefAcquired.getAndSet(false)) this.world.releaseRef();
        if (this.zipSource != null) {
            try { this.zipSource.close(); }
            catch (IOException e) { Logger.error("Failed to close ZIP import source", e); }
        }
    }

    private interface IImporterMethod <T> {
        void importRegion(T file) throws Exception;
    }

    private volatile Thread worker;
    private IUpdateCallback updateCallback;
    private ICompletionCallback completionCallback;
    public void importRegionDirectoryAsync(File directory) {
        var files = directory.listFiles((dir, name) -> {
            var sections = name.split("\\.");
            if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                Logger.error("Unknown file: " + name);
                return false;
            }
            return true;
        });
        if (files == null) {
            return;
        }
        Arrays.sort(files, File::compareTo);
        this.importRegionsAsync(files, this::importRegionFile);
    }

    public void importZippedRegionDirectoryAsync(File zip, String innerDirectory) {
        try {
            innerDirectory = innerDirectory.replace("\\\\", "\\").replace("\\", "/");
            var file = ZipFile.builder().setFile(zip).get();
            this.zipSource = file;
            ArrayList<ZipArchiveEntry> regions = new ArrayList<>();
            for (var e = file.getEntries(); e.hasMoreElements();) {
                var entry = e.nextElement();
                if (entry.isDirectory()||!entry.getName().startsWith(innerDirectory)) {
                    continue;
                }
                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");
                if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                    Logger.error("Unknown file: " + name);
                    continue;
                }
                regions.add(entry);
            }
            this.importRegionsAsync(regions.toArray(ZipArchiveEntry[]::new), (entry)->{
                if (entry.getSize() == 0) {
                    return;
                }
                if (entry.getSize() < 0 || entry.getSize() > Integer.MAX_VALUE) {
                    throw new IOException("ZIP region exceeds supported buffer size: " + entry.getName());
                }
                var buf = new MemoryBuffer(entry.getSize());
                try {
                    try (var channel = Channels.newChannel(file.getInputStream(entry))) {
                        var buffer = buf.asByteBuffer();
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffer) <= 0) throw new IOException("Truncated ZIP region: " + entry.getName());
                        }
                    }
                    var parts = entry.getName().split("/");
                    var name = parts[parts.length-1];
                    var sections = name.split("\\.");
                    this.importRegion(buf, Integer.parseInt(sections[1]), Integer.parseInt(sections[2]));
                } catch (NumberFormatException e) {
                    Logger.error("Invalid region position, skipping ZIP entry: " + entry.getName());
                } finally {
                    buf.free();
                }
            });
        } catch (Exception e) {
            this.cleanup();
            throw new RuntimeException(e);
        }

    }

    private <T> void importRegionsAsync(T[] regionFiles, IImporterMethod<T> importer) {
        this.totalChunks.set(0);
        this.estimatedTotalChunks.set(0);
        this.chunksProcessed.set(0);
        this.worker = new Thread(() -> {
            try {
                this.estimatedTotalChunks.addAndGet(regionFiles.length*1024);
                for (var file : regionFiles) {
                    this.estimatedTotalChunks.addAndGet(-1024);
                    try {
                        importer.importRegion(file);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    while (this.service.numJobs() > 10_000 && this.isRunning) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    if (!this.isRunning) {
                        return;
                    }
                }
                this.service.blockTillEmpty();
            } catch (Exception e) {
                this.isRunning = false;
                Logger.error("World import failed; source data preserved", e);
            } finally {
                try { this.cleanup(); }
                finally {
                    this.isRunning = false;
                    this.worker = null;
                    this.completionCallback.onCompletion(this.chunksProcessed.get());
                }
            }
        });
        this.worker.setName("World importer");
    }

    public boolean isBusy() {
        return this.isRunning || this.worker != null;
    }

    public boolean isRunning() {
        return this.isRunning || (this.worker != null && this.worker.isAlive());
    }

    private void importRegionFile(File file) throws IOException {
        var name = file.getName();
        var sections = name.split("\\.");
        if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
            Logger.error("Unknown file: " + name);
            throw new IllegalStateException();
        }
        int rx = 0;
        int rz = 0;
        try {
            rx = Integer.parseInt(sections[1]);
            rz = Integer.parseInt(sections[2]);
        } catch (NumberFormatException e) {
            Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
            return;
        }
        try (var fileStream = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            if (fileStream.size() == 0) {
                return;
            }
            if (fileStream.size() > Integer.MAX_VALUE) throw new IOException("Region file exceeds supported buffer size: " + file);
            var fileData = new MemoryBuffer(fileStream.size());
            try {
                var buffer = fileData.asByteBuffer();
                while (buffer.hasRemaining()) {
                    int read = fileStream.read(buffer);
                    if (read <= 0) throw new IOException("Region file was truncated while reading " + file);
                }
                this.importRegion(fileData, rx, rz);
            } finally {
                fileData.free();
            }
        }
    }


    private void importRegion(MemoryBuffer regionFile, int x, int z) {
        //Find and load all saved chunks
        if (regionFile.size < 8192) {//File not big enough
            Logger.warn("Header of region file invalid");
            return;
        }
        for (int idx = 0; idx < 1024; idx++) {
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(regionFile.address+idx*4));//Assumes little endian
            if (sectorMeta == 0) {
                //Empty chunk
                continue;
            }
            int sectorStart = sectorMeta>>>8;
            int sectorCount = sectorMeta&((1<<8)-1);

            if (sectorCount == 0) {
                continue;
            }

            //TODO: create memory copy for each section
            if (sectorStart < 2 || regionFile.size < (sectorCount + (long)sectorStart) * 4096L) {
                Logger.warn("Cannot access chunk sector as it goes out of bounds. start bytes: " + (sectorStart*4096) + " sector count: " + sectorCount + " fileSize: " + regionFile.size);
                continue;
            }

            {
                long base = regionFile.address + sectorStart * 4096L;
                int chunkLen = sectorCount * 4096;
                int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                byte b = MemoryUtil.memGetByte(base + 4L);
                if (m <= 1) {
                    Logger.error("Chunk is allocated, but stream is missing");
                } else {
                    int n = m - 1;
                    if (regionFile.size < (5L + n + sectorStart*4096L)) {
                        Logger.warn("Chunk stream to small");
                    } else if ((b & 128) != 0) {
                        if (n != 0) {
                            Logger.error("Chunk has both internal and external streams");
                        }
                        Logger.error("Chunk has external stream which is not supported");
                    } else if (n > chunkLen-5) {
                        Logger.error("Chunk stream is truncated: expected "+n+" but read " + (chunkLen-5));
                    } else if (n < 0) {
                        Logger.error("Declared size of chunk is negative");
                    } else {
                        var data = new MemoryBuffer(n).cpyFrom(base + 5);
                        this.jobQueue.add(()-> {
                            if (!this.isRunning) {
                                data.free();
                                return;
                            }
                            try {
                                try (var decompressedData = this.decompress(b, data)) {
                                    if (decompressedData == null) {
                                        Logger.error("Error decompressing chunk data");
                                    } else {
                                        var nbt = NbtIo.read(decompressedData, NbtAccounter.create(64L * 1024 * 1024));
                                        this.importChunkNBT(nbt, x, z);
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                data.free();
                            }
                        });
                        this.totalChunks.incrementAndGet();
                        this.estimatedTotalChunks.incrementAndGet();
                        this.service.execute();
                    }
                }
            }
        }
    }

    private static InputStream createInputStream(MemoryBuffer data) {
        return new me.cortex.voxy.common.util.ByteBufferBackedInputStream(data.asByteBuffer());
    }

    private DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
        RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
        if (chunkStreamVersion == null) {
            Logger.error("Chunk has invalid chunk stream version");
            return null;
        } else {
            return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
        }
    }

    private void importChunkNBT(CompoundTag chunk, int regionX, int regionZ) {
        if (!chunk.contains("Status")) {
            //Its not real so decrement the chunk
            this.totalChunks.decrementAndGet();
            return;
        }

        //Dont process non full chunk sections
        var status = ChunkStatus.byName(chunk.getStringOr("Status", null));
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {//We also import empty since they are from data upgrade
            this.totalChunks.decrementAndGet();
            return;
        }

        try {
            if (!(chunk.get("xPos") instanceof net.minecraft.nbt.IntTag)
                    || !(chunk.get("zPos") instanceof net.minecraft.nbt.IntTag)) {
                Logger.warn("Skipping imported chunk with invalid coordinate types");
                return;
            }
            int x = chunk.getIntOr("xPos", Integer.MIN_VALUE);
            int z = chunk.getIntOr("zPos", Integer.MIN_VALUE);
            if (!isSupportedChunkPosition(x, z) || x>>5 != regionX || z>>5 != regionZ) {
                Logger.error("Chunk position is not located in correct region, skipping chunk: " + x + ", " + z);
                return;
            }

            for (var sectionE : chunk.getList("sections").orElseThrow()) {
                var section = (CompoundTag) sectionE;
                int y = section.getIntOr("Y", Integer.MIN_VALUE);
                if (y < this.minSectionY || y >= this.maxSectionY) {
                    Logger.warn("Skipping imported section outside target world height: " + y);
                    continue;
                }
                this.importSectionNBT(x, y, z, section);
            }
        } catch (Exception e) {
            Logger.error("Exception importing world chunk:",e);
        }

        this.updateCallback.onUpdate(this.chunksProcessed.incrementAndGet(), this.estimatedTotalChunks.get());
    }

    private static final byte[] EMPTY = new byte[0];
    static boolean isSupportedChunkPosition(long x, long z) {
        long limit = Level.MAX_LEVEL_SIZE / 16;
        return x >= -limit && x < limit && z >= -limit && z < limit;
    }
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void importSectionNBT(int x, int y, int z, CompoundTag section) {
        if (section.getCompound("block_states").isEmpty()) {
            return;
        }

        byte[] blockLightData = section.getByteArray("BlockLight").orElse(EMPTY);
        byte[] skyLightData = section.getByteArray("SkyLight").orElse(EMPTY);

        DataLayer blockLight;
        if (blockLightData.length != 0) {
            blockLight = new DataLayer(blockLightData);
        } else {
            blockLight = null;
        }

        DataLayer skyLight;
        if (skyLightData.length != 0) {
            skyLight = new DataLayer(skyLightData);
        } else {
            skyLight = null;
        }

        var blockStatesRes = blockStateCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states").get());
        if (blockStatesRes.isError()) {
            Logger.warn("Skipping imported section with undecodable block palette at " + x + "," + y + "," + z);
            return;
        }
        var blockStates = blockStatesRes.getOrThrow();
        var biomes = this.defaultBiomeProvider;
        var optBiomes = section.getCompound("biomes");
        if (optBiomes.isPresent()) {
            var result = this.biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get());
            if (result.isError()) {
                Logger.warn("Skipping imported section with undecodable biome palette at " + x + "," + y + "," + z);
                return;
            }
            biomes = result.getOrThrow();
        }
        VoxelizedSection csec = WorldConversionFactory.convert(
                SECTION_CACHE.get().setPosition(x, y, z),
                this.world.getMapper(),
                blockStates,
                biomes,
                (bx, by, bz) -> {
                    int block = 0;
                    int sky = 0;
                    if (blockLight != null) {
                        block = blockLight.get(bx, by, bz);
                    }
                    if (skyLight != null) {
                        sky = skyLight.get(bx, by, bz);
                    }
                    return (byte) (sky|(block<<4));
                }
        );

        WorldVoxilizedSectionMipper.mipSection(csec, this.world.getMapper());
        WorldUpdater.insertUpdate(this.world, csec);
    }
}
