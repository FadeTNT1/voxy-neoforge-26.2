package me.cortex.voxy;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone runner; requires Minecraft/NeoForge's initialized registry runtime. */
public final class MapperPersistenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        runChecks();
    }

    public static void runChecks() throws Exception {
        checkPreservation();
        checkFailedPersistence();
        checkPublication();
        checkInvalidIds();
        checkOversizedNbt();
        System.out.println("Mapper persistence checks passed.");
    }

    private static void checkPreservation() throws Exception {
        var storage = new Storage();
        byte[] unknown = block(1, "missing:removed_block", null);
        byte[] invalidProperty = block(2, "minecraft:oak_log", "invalid_axis");
        byte[] stone = block(3, "minecraft:stone", null);
        byte[] duplicateStone = block(4, "minecraft:stone", null);
        byte[] missingBiome = biome(0, null);
        byte[] invalidBiome = biome(1, "Invalid biome!");
        byte[] customBiome = biome(2, "missing:removed_biome");
        storage.data.put((1 << 30) | 1, unknown);
        storage.data.put((1 << 30) | 2, invalidProperty);
        storage.data.put((1 << 30) | 3, stone);
        storage.data.put((1 << 30) | 4, duplicateStone);
        storage.data.put(2 << 30, missingBiome);
        storage.data.put((2 << 30) | 1, invalidBiome);
        storage.data.put((2 << 30) | 2, customBiome);
        var mapper = new Mapper(storage);
        require(storage.writes == 0, "Loading must never rewrite stored mappings");
        require(mapper.getBlockStateCount() == 5, "Unresolved IDs remain reserved");
        require(mapper.getBlockStateFromBlockId(1).isAir(), "Missing block uses deterministic display fallback");
        require(mapper.getBlockStateFromBlockId(2).isAir(), "Lenient property decoding must not alias default state");
        require(mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()) == 3, "Lowest duplicate ID wins without overwrite");
        int newId = mapper.getIdForBlockState(Blocks.OAK_LOG.defaultBlockState());
        require(newId == 5, "New block cannot reuse unresolved IDs");
        var entries = mapper.getBiomeEntries();
        require(entries[0].biome.equals("minecraft:plains") && entries[1].biome.equals("minecraft:plains"),
                "Malformed biomes use deterministic valid fallback");
        require(entries[2].biome.equals("missing:removed_biome"), "Valid missing biome identifier preserved");
        var plains = Holder.Reference.createStandAlone(new HolderOwner<Biome>() { }, Biomes.PLAINS);
        require(mapper.getIdForBiome(plains) == 3, "Malformed biome display fallback cannot own a reverse mapping");
        mapper.forceResaveStates();
        require(Arrays.equals(unknown, storage.data.get((1 << 30) | 1)), "Missing block bytes preserved");
        require(Arrays.equals(invalidProperty, storage.data.get((1 << 30) | 2)), "Invalid property bytes preserved");
        require(Arrays.equals(stone, storage.data.get((1 << 30) | 3)), "Resolved mapping bytes preserved");
        require(Arrays.equals(duplicateStone, storage.data.get((1 << 30) | 4)), "Duplicate mapping bytes preserved");
        require(Arrays.equals(missingBiome, storage.data.get(2 << 30)), "Missing biome bytes preserved");
        require(Arrays.equals(invalidBiome, storage.data.get((2 << 30) | 1)), "Invalid biome bytes preserved");
        require(Arrays.equals(customBiome, storage.data.get((2 << 30) | 2)), "Custom biome bytes preserved");
        var reloaded = new Mapper(storage);
        require(reloaded.getBlockStateCount() == 6 && reloaded.getIdForBlockState(Blocks.OAK_LOG.defaultBlockState()) == 5,
                "Reopen retains all IDs and mappings");
        require(reloaded.getIdForBiome(plains) == 3, "Reopen retains newly allocated biome ID");
    }

    private static void checkOversizedNbt() throws Exception {
        var tag = new CompoundTag();
        tag.putInt("id", 1);
        tag.putByteArray("oversized", new byte[2 << 20]);
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, output);
        byte[] original = output.toByteArray();
        var storage = new Storage();
        storage.data.put((1 << 30) | 1, original);
        try {
            new Mapper(storage);
            throw new AssertionError("Oversized mapping NBT accepted");
        } catch (net.minecraft.nbt.NbtAccounterException expected) { }
        require(storage.writes == 0 && Arrays.equals(original, storage.data.get((1 << 30) | 1)),
                "Oversized mapping NBT rejected without rewriting original");
    }

    private static void checkFailedPersistence() {
        var storage = new Storage();
        var mapper = new Mapper(storage);
        storage.failWrite = true;
        expectFailure(() -> mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()));
        require(mapper.getBlockStateCount() == 1, "Failed put must not publish an ID");
        storage.failWrite = false;
        storage.failFlush = true;
        expectFailure(() -> mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()));
        require(mapper.getBlockStateCount() == 1, "Failed flush must not publish an ID");
        storage.failFlush = false;
        require(mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()) == 1, "Failure releases lock and retry preserves ID");
        require(storage.flushes == 1, "Successful registration flushes before returning");
    }

    private static void checkPublication() throws Exception {
        var storage = new Storage();
        var mapper = new Mapper(storage);
        storage.entered = new CountDownLatch(1);
        storage.release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var finished = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try { mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()); }
            catch (Throwable error) { failure.set(error); }
        });
        Thread second = new Thread(() -> {
            secondStarted.countDown();
            try { mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()); }
            catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        first.start();
        try {
            require(storage.entered.await(5, TimeUnit.SECONDS), "First mapping reaches storage");
            second.start();
            require(secondStarted.await(5, TimeUnit.SECONDS), "Second registration starts");
            require(!finished.await(100, TimeUnit.MILLISECONDS), "Concurrent lookup cannot observe mapping before put finishes");
        } finally {
            storage.release.countDown();
            first.join(5000);
            if (second.getState() != Thread.State.NEW) second.join(5000);
        }
        require(!first.isAlive() && !second.isAlive(), "Mapping registration threads finish");
        if (failure.get() != null) throw new AssertionError("Registration failed", failure.get());
        require(storage.writes == 1 && storage.flushes == 1, "Concurrent registration persists exactly once");
    }

    private static void checkInvalidIds() throws Exception {
        var invalidBlock = new Storage();
        invalidBlock.data.put((1 << 30) | (1 << 20), block(1 << 20, "minecraft:stone", null));
        expectFailure(() -> new Mapper(invalidBlock));
        require(invalidBlock.writes == 0, "Out-of-range block IDs fail without writes");
        var invalidBiome = new Storage();
        invalidBiome.data.put((2 << 30) | 512, biome(512, "minecraft:plains"));
        expectFailure(() -> new Mapper(invalidBiome));
        require(invalidBiome.writes == 0, "Out-of-range biome IDs fail without writes");
        var gap = new Storage();
        gap.data.put((1 << 30) | 2, block(2, "minecraft:stone", null));
        expectFailure(() -> new Mapper(gap));
        require(gap.writes == 0, "Mapping gaps fail without reusing or writing IDs");
        var fullBiomes = new Storage();
        for (int id = 0; id < 512; id++) fullBiomes.data.put((2 << 30) | id, biome(id, "minecraft:plains"));
        var fullMapper = new Mapper(fullBiomes);
        var desert = Holder.Reference.createStandAlone(new HolderOwner<Biome>() { }, Biomes.DESERT);
        expectFailure(() -> fullMapper.getIdForBiome(desert));
        require(fullBiomes.writes == 0 && fullMapper.getBiomeEntries().length == 512,
                "Biome allocation cannot overflow into packed light bits");
    }

    private static byte[] block(int id, String name, String axis) throws Exception {
        var state = new CompoundTag();
        state.putString("Name", name);
        if (axis != null) {
            var properties = new CompoundTag();
            properties.putString("axis", axis);
            state.put("Properties", properties);
        }
        var root = new CompoundTag();
        root.putInt("id", id);
        root.put("block_state", state);
        return compressed(root);
    }

    private static byte[] biome(int id, String name) throws Exception {
        var root = new CompoundTag();
        root.putInt("id", id);
        if (name != null) root.putString("biome_id", name);
        return compressed(root);
    }

    private static byte[] compressed(CompoundTag root) throws Exception {
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, output);
        return output.toByteArray();
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected storage failure");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class Storage implements IMappingStorage {
        final Int2ObjectOpenHashMap<byte[]> data = new Int2ObjectOpenHashMap<>();
        int writes;
        int flushes;
        boolean failWrite;
        boolean failFlush;
        CountDownLatch entered;
        CountDownLatch release;

        @Override public void putIdMapping(int id, ByteBuffer buffer) {
            if (failWrite) throw new IllegalStateException("Injected put failure");
            if (entered != null) {
                entered.countDown();
                try { require(release.await(5, TimeUnit.SECONDS), "Storage release timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            data.put(id, bytes);
            writes++;
        }
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(data); }
        @Override public void flush() {
            if (failFlush) throw new IllegalStateException("Injected flush failure");
            flushes++;
        }
        @Override public void close() { }
    }
}
