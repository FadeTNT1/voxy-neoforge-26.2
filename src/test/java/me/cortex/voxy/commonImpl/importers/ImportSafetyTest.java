package me.cortex.voxy.commonImpl.importers;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Random;

public final class ImportSafetyTest {
    public static void main(String[] args) throws Exception {
        require(WorldImporter.isSupportedChunkPosition(0, 0), "Valid chunk position");
        require(!WorldImporter.isSupportedChunkPosition(33554432, 0), "Aliasing MCA chunk rejected");
        require(!WorldImporter.isSupportedChunkPosition(Long.MAX_VALUE, 0), "Oversized MCA coordinate rejected");
        require(DHImporter.isSupportedTilePosition(0, 0), "Valid DH tile position");
        require(!DHImporter.isSupportedTilePosition(8388608, 0), "Aliasing DH tile rejected");
        require(!DHImporter.isSupportedTilePosition(Long.MAX_VALUE, 0), "Overflowing DH tile rejected");
        var contextClass = Class.forName(DHImporter.class.getName() + "$WorkCTX");
        var constructor = contextClass.getDeclaredConstructor(PreparedStatement.class, int.class);
        constructor.setAccessible(true);
        var context = constructor.newInstance(null, 16);
        var decompress = DHImporter.class.getDeclaredMethod("createDecompressedStream", int.class, InputStream.class, contextClass);
        decompress.setAccessible(true);
        var free = contextClass.getDeclaredMethod("free");
        free.setAccessible(true);
        byte[] expected = new byte[50000];
        new Random(262).nextBytes(expected);
        var xzBytes = new ByteArrayOutputStream();
        try (var encoder = new org.tukaani.xz.XZOutputStream(xzBytes, new org.tukaani.xz.LZMA2Options(1))) {
            encoder.write(expected);
        }
        try (var result = (InputStream) decompress.invoke(null, 3, new ByteArrayInputStream(xzBytes.toByteArray()), context)) {
            require(Arrays.equals(expected, result.readAllBytes()), "DH XZ round trip within memory limit");
        }
        var source = MemoryUtil.memAlloc(expected.length).put(expected).flip();
        var compressed = MemoryUtil.memAlloc(Math.toIntExact(Zstd.ZSTD_compressBound(expected.length)));
        try {
            long size = Zstd.ZSTD_compress(compressed, source, 3);
            require(!Zstd.ZSTD_isError(size) && size > 8196, "Large compressed fixture");
            byte[] frame = new byte[Math.toIntExact(size)];
            compressed.limit(frame.length).get(frame);
            try (var result = (InputStream) decompress.invoke(null, 4, new ByteArrayInputStream(frame), context)) {
                require(Arrays.equals(expected, result.readAllBytes()), "DH ZSTD round trip above old input buffer size");
            }
            try {
                decompress.invoke(null, 4, new ByteArrayInputStream(Arrays.copyOf(frame, frame.length - 1)), context);
                throw new AssertionError("Truncated ZSTD frame accepted");
            } catch (InvocationTargetException failure) {
                require(failure.getCause() instanceof IOException, "Truncated frame fails as input error");
            }
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(compressed);
            free.invoke(context);
        }

        byte[] column = new byte[65536];
        try (var input = new DataInputStream(new ByteArrayInputStream(new byte[]{0, 1, 9}))) {
            try { DHImporter.readColumn(input, column); throw new AssertionError("Truncated DH column accepted"); }
            catch (IOException expectedFailure) { }
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(new byte[]{0x7f, (byte) 0xff}))) {
            try { DHImporter.readColumn(input, column); throw new AssertionError("Oversized DH column accepted"); }
            catch (IOException expectedFailure) { }
        }

        var database = Files.createTempFile(Path.of("build"), "dh-source-", ".sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source (value INTEGER)");
            statement.execute("INSERT INTO source VALUES (262)");
        }
        byte[] original = Files.readAllBytes(database);
        try (var connection = DHImporter.openReadOnlyDatabase(database.toFile());
             var statement = connection.createStatement()) {
            try { statement.execute("UPDATE source SET value = 263"); throw new AssertionError("Source database writable"); }
            catch (SQLException expectedFailure) { }
        }
        require(Arrays.equals(original, Files.readAllBytes(database)), "DH source database unchanged");
        System.out.println("Import safety passed: large DH frames, truncated columns, bounded lengths, read-only source database.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
