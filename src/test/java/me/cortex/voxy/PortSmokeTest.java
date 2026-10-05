package me.cortex.voxy;

import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.function.Function;

/** Exercises native loading, persisted data, and compression under Java 25. */
public final class PortSmokeTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory(Path.of("build"), "native-smoke-");
        checkStorage(root.resolve("rocksdb"), RocksDBStorageBackend::new);
        checkStorage(Files.createDirectory(root.resolve("lmdb")), LMDBStorageBackend::new);
        checkCompression(new ZSTDCompressor(3));
        checkCompression(new LZ4Compressor());
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 26 + 2")) {
            require(result.next() && result.getInt(1) == 28, "SQLite native query");
        }
        System.out.println("Native smoke passed: RocksDB/LMDB persistence, ZSTD/LZ4 round trips, SQLite.");
    }

    private static void checkStorage(Path path, Function<String, StorageBackend> open) {
        var input = new MemoryBuffer(4096);
        var scratch = new MemoryBuffer(8192);
        byte[] expected = pattern();
        input.asByteBuffer().put(expected);
        long key = 0x2123456789ABCDEFL;
        byte[] mapping = {26, 2, 0, 88};
        StorageBackend backend = open.apply(path.toString());
        try {
            require(backend.getSectionData(key, scratch.createUntrackedUnfreeableReference()) == null, "Missing section");
            backend.setSectionData(key, input);
            backend.putIdMapping(27, ByteBuffer.allocateDirect(mapping.length).put(mapping).flip());
            backend.flush();
        } finally { backend.close(); }
        backend = open.apply(path.toString());
        try {
            require(Arrays.equals(expected, bytes(backend.getSectionData(key, scratch.createUntrackedUnfreeableReference()))), "Persisted section: " + path);
            require(Arrays.equals(mapping, backend.getIdMappingsData().get(27)), "Persisted ID mapping: " + path);
            backend.deleteSectionData(key);
            require(backend.getSectionData(key, scratch.createUntrackedUnfreeableReference()) == null, "Deleted section: " + path);
        } finally {
            backend.close();
            input.free();
            scratch.free();
        }
    }

    private static void checkCompression(StorageCompressor compressor) {
        var input = new MemoryBuffer(4096);
        input.asByteBuffer().put(pattern());
        // Compressors reuse thread-local scratch; persisted input must be separate.
        var compressed = compressor.compress(input).copy();
        try {
            require(Arrays.equals(pattern(), bytes(compressor.decompress(compressed))),
                    compressor.getClass().getSimpleName() + " round trip");
        } finally {
            input.free();
            compressed.free();
            compressor.close();
        }
    }

    private static byte[] pattern() {
        byte[] result = new byte[4096];
        for (int i = 0; i < result.length; i++) result[i] = (byte)(i % 17);
        return result;
    }

    private static byte[] bytes(MemoryBuffer buffer) {
        require(buffer != null, "Expected data");
        byte[] result = new byte[Math.toIntExact(buffer.size)];
        buffer.asByteBuffer().get(result);
        return result;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
