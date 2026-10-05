package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class WorldImportLifecycleTest {
    @Test
    void malformedSourcesFinishWithoutChangingSourceOrCachedTerrain(MinecraftServer server) throws Exception {
        var root = Files.createTempDirectory("voxy-import-lifecycle-");
        var backend = new MemoryStorageBackend() {
            final AtomicInteger writes = new AtomicInteger();
            @Override public void setSectionData(long key, MemoryBuffer data) {
                writes.incrementAndGet();
                super.setSectionData(key, data);
            }
        };
        var engine = new WorldEngine(new SectionSerializationStorage(backend));
        engine.setSaveCallback((world, section, nonBlocking, acquired) -> {
            world.storage.saveSection(section);
            section.setNotDirty();
            return false;
        });
        var services = new ServiceManager(count -> { });
        try {
            for (boolean zip : new boolean[]{false, true}) {
                var directory = Files.createTempDirectory(root, "alias-");
                byte[] source = region(33554432, "minecraft:stone");
                var region = directory.resolve("r.1048576.0.mca");
                Files.write(region, source);
                var importer = new WorldImporter(engine, server.registryAccess(), -64, 384, services, () -> true);
                if (zip) {
                    var archive = directory.resolve("regions.zip");
                    try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
                        output.putNextEntry(new ZipEntry("region/r.1048576.0.mca"));
                        output.write(source);
                        output.closeEntry();
                    }
                    importer.importZippedRegionDirectoryAsync(archive.toFile(), "region/");
                } else importer.importRegionDirectoryAsync(directory.toFile());
                finish(importer, services);
                assertArrayEquals(source, Files.readAllBytes(region), "MCA source changed");
                assertEquals(0, backend.writes.get(), "Aliasing coordinate wrote cached terrain");
            }

            var missingBlockDirectory = Files.createDirectory(root.resolve("missing-block"));
            Files.write(missingBlockDirectory.resolve("r.0.0.mca"), region(0, "missing:removed_block"));
            var missingBlock = new WorldImporter(engine, server.registryAccess(), -64, 384, services, () -> true);
            missingBlock.importRegionDirectoryAsync(missingBlockDirectory.toFile());
            finish(missingBlock, services);
            assertEquals(0, backend.writes.get(), "Unresolved palette overwrote cached terrain");

            // SQL metadata is readable, but the per-worker data query cannot be prepared.
            var database = root.resolve("malformed-dh.sqlite");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE FullData (DetailLevel INTEGER, PosX INTEGER, PosZ INTEGER, CompressionMode INTEGER, DataFormatVersion INTEGER)");
                statement.execute("INSERT INTO FullData VALUES (0, 0, 0, 3, 1)");
            }
            byte[] originalDatabase = Files.readAllBytes(database);
            finish(new DHImporter(database.toFile(), engine, server.registryAccess(), -64, 384, services, () -> true), services);
            assertArrayEquals(originalDatabase, Files.readAllBytes(database), "Malformed DH source changed");
            assertEquals(0, backend.writes.get(), "Malformed DH source wrote terrain");

            // A valid singleton palette still imports after rejected sources.
            var validDirectory = Files.createDirectory(root.resolve("valid"));
            Files.write(validDirectory.resolve("r.0.0.mca"), region(0, "minecraft:stone"));
            var valid = new WorldImporter(engine, server.registryAccess(), -64, 384, services, () -> true);
            valid.importRegionDirectoryAsync(validDirectory.toFile());
            finish(valid, services);
            assertTrue(backend.writes.get() > 0, "Valid MCA stopped importing");
            assertEquals(0, engine.getActiveSectionCount(), "Imports leaked sections");
            assertFalse(engine.isWorldUsed(), "Imports leaked a world reference");
        } finally {
            services.shutdown();
            engine.free();
        }
    }

    private static void finish(IDataImporter importer, ServiceManager services) throws Exception {
        var completed = new CountDownLatch(1);
        importer.runImport((done, total) -> { }, count -> completed.countDown());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try {
            while (completed.getCount() != 0 && System.nanoTime() < deadline) {
                services.tryRunAJob();
                Thread.sleep(1);
            }
            assertEquals(0, completed.getCount(), "Malformed import did not complete");
            assertFalse(importer.isRunning(), "Completed import stayed active");
        } finally {
            // Keep a broken regression from blocking the entire test JVM forever.
            var stopping = Thread.ofVirtual().start(importer::shutdown);
            stopping.join(3000);
            assertFalse(stopping.isAlive(), "Importer shutdown blocked");
        }
    }

    private static byte[] region(int chunkX, String blockName) throws Exception {
        var state = new CompoundTag();
        state.putString("Name", blockName);
        var palette = new ListTag();
        palette.add(state);
        var blocks = new CompoundTag();
        blocks.put("palette", palette);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put("block_states", blocks);
        var sections = new ListTag();
        sections.add(section);
        var chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("xPos", chunkX);
        chunk.putInt("zPos", 0);
        chunk.put("sections", sections);
        var output = new ByteArrayOutputStream();
        NbtIo.write(chunk, new DataOutputStream(output));
        byte[] data = output.toByteArray();
        assertTrue(data.length < 4091);
        var region = ByteBuffer.allocate(12288);
        region.putInt(0, (2 << 8) | 1);
        region.position(8192).putInt(data.length + 1).put((byte) 3).put(data);
        return region.array();
    }
}
