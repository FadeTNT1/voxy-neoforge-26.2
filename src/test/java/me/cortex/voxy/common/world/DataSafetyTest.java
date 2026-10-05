package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend;
import me.cortex.voxy.common.config.storage.other.DelegatingStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Function;

/** Standalone regression checks for rejecting damaged persisted data without replacing it. */
public final class DataSafetyTest {
    public static void main(String[] args) throws Exception {
        checkSerialization();
        checkCompression(new ZSTDCompressor(3));
        checkCompression(new LZ4Compressor());
        var root = Files.createTempDirectory(Path.of("build"), "data-safety-");
        checkStorage(root.resolve("rocksdb"), RocksDBStorageBackend::new);
        checkStorage(Files.createDirectory(root.resolve("lmdb")), LMDBStorageBackend::new);
        System.out.println("Data safety passed: bounded decoding, unchanged rejected sections, preserved records, disabled writes, checked compression.");
    }

    private static WorldSection source() {
        var section = new WorldSection(1, 2, 3, 4, null);
        for (int i = 0; i < section.data.length; i++) section.data[i] = 0x1234000000000000L + i;
        section.nonEmptyChildren = (byte) 0xA5;
        return section;
    }

    private static void checkSerialization() {
        var source = source();
        var data = SaveLoadSystem3.serialize(source).copy();
        try {
            var target = new WorldSection(1, 2, 3, 4, null);
            require(SaveLoadSystem3.deserialize(target, data), "Full palette accepted");
            require(Arrays.equals(source.data, target.data), "Full palette round trip");
            require(target.nonEmptyChildren == source.nonEmptyChildren, "Children round trip");

            long metadata = MemoryUtil.memGetLong(data.address + 8);
            // Removing the palette-length guard must fail without changing the target.
            MemoryUtil.memPutLong(data.address + 8, metadata & ~0xFFFFL);
            rejectedUnchanged(data, "Zero palette");
            MemoryUtil.memPutLong(data.address + 8, (metadata & ~0xFFFFL) | 0xFFFFL);
            rejectedUnchanged(data, "Palette larger than volume");
            MemoryUtil.memPutLong(data.address + 8, metadata);

            for (long length : new long[] {-1, 0, 1, 7, 8, 15, 16, 65551, data.size - 1}) {
                rejectedUnchanged(MemoryBuffer.createUntrackedUnfreeableRawFrom(data.address, length), "Truncated section " + length);
            }
            short index = MemoryUtil.memGetShort(data.address + 16);
            MemoryUtil.memPutShort(data.address + 16, (short) 0xFFFF);
            rejectedUnchanged(data, "Unsigned index outside palette");
            MemoryUtil.memPutShort(data.address + 16, index);
            long lastIndex = data.address + 16 + (WorldSection.SECTION_VOLUME - 1) * 2L;
            short last = MemoryUtil.memGetShort(lastIndex);
            MemoryUtil.memPutShort(lastIndex, (short) 0xFFFF);
            rejectedUnchanged(data, "Late invalid index cannot partially change target");
            MemoryUtil.memPutShort(lastIndex, last);
            MemoryUtil.memPutLong(data.address, source.key ^ 1);
            rejectedUnchanged(data, "Wrong key");
        } finally { data.free(); }

        Arrays.fill(source.data, 42);
        data = SaveLoadSystem3.serialize(source).copy();
        try {
            var target = new WorldSection(1, 2, 3, 4, null);
            require(SaveLoadSystem3.deserialize(target, data) && Arrays.equals(source.data, target.data), "Single palette round trip");
        } finally { data.free(); }
    }

    private static void rejectedUnchanged(MemoryBuffer data, String description) {
        var target = new WorldSection(1, 2, 3, 4, null);
        Arrays.fill(target.data, 0x76543210L);
        target.nonEmptyChildren = 0x37;
        target.nonEmptyBlockCount = 91;
        target.metadata = 123;
        long[] before = target.data.clone();
        require(!SaveLoadSystem3.deserialize(target, data), description + " rejected");
        require(Arrays.equals(before, target.data) && target.nonEmptyChildren == 0x37
                && target.nonEmptyBlockCount == 91 && target.metadata == 123, description + " leaves target unchanged");
    }

    private static void checkCompression(StorageCompressor compressor) {
        var input = new MemoryBuffer(4096);
        for (int i = 0; i < input.size; i++) MemoryUtil.memPutByte(input.address + i, (byte) (i % 17));
        var frame = compressor.compress(input).copy();
        try {
            require(Arrays.equals(bytes(input), bytes(compressor.decompress(frame))), "Compression round trip " + compressor.getClass().getSimpleName());
            mustFail(() -> compressor.decompress(MemoryBuffer.createUntrackedUnfreeableRawFrom(frame.address, frame.size - 1)), "Truncated compressed frame");
            mustFail(() -> compressor.decompress(MemoryBuffer.createUntrackedUnfreeableRawFrom(frame.address, 0)), "Empty compressed frame");
            if (compressor instanceof ZSTDCompressor) {
                var legacy = new MemoryBuffer(Zstd.ZSTD_COMPRESSBOUND(input.size));
                try {
                    long size = Zstd.nZSTD_compress(legacy.address, legacy.size, input.address, input.size, 3);
                    require(!Zstd.ZSTD_isError(size), "Legacy ZSTD fixture compressed");
                    require(Arrays.equals(bytes(input), bytes(compressor.decompress(
                            MemoryBuffer.createUntrackedUnfreeableRawFrom(legacy.address, size)))), "Checksum-less legacy ZSTD remains readable");
                } finally { legacy.free(); }
                // New frames carry a checksum; altering only that checksum must fail.
                MemoryUtil.memPutByte(frame.address + frame.size - 1, (byte) (MemoryUtil.memGetByte(frame.address + frame.size - 1) ^ 1));
                mustFail(() -> compressor.decompress(frame), "ZSTD checksum mismatch");
            } else {
                for (int size : new int[] {0, -1, Integer.MAX_VALUE}) {
                    MemoryUtil.memPutInt(frame.address, size);
                    mustFail(() -> compressor.decompress(frame), "Invalid LZ4 output length " + size);
                }
                mustFail(() -> compressor.decompress(MemoryBuffer.createUntrackedUnfreeableRawFrom(frame.address, 3)), "Truncated LZ4 header");
            }
        } finally {
            frame.free();
            input.free();
            compressor.close();
        }
    }

    private static void checkStorage(Path path, Function<String, StorageBackend> open) {
        var backend = open.apply(path.toString());
        var storage = new SectionSerializationStorage(backend);
        var source = source();
        var record = SaveLoadSystem3.serialize(source).copy();
        var scratch = new MemoryBuffer(record.size);
        var small = new MemoryBuffer(64).zero();
        try {
            backend.setSectionData(source.key, record);
            var failedWriter = new SectionSerializationStorage(new DelegatingStorageAdaptor(backend) {
                @Override public void setSectionData(long key, MemoryBuffer data) {
                    throw new IllegalStateException("Injected write failure");
                }
            });
            mustFail(() -> failedWriter.saveSection(source), "Backend write failure surfaced");
            mustFail(() -> failedWriter.putIdMapping(20, ByteBuffer.allocateDirect(1).put((byte) 1).flip()), "Mapping writes disabled after backend write failure");
            require(!backend.getIdMappingsData().containsKey(20), "Failed writer did not publish mapping");
            mustFail(() -> backend.getSectionData(source.key, MemoryBuffer.createUntrackedUnfreeableRawFrom(small.address, 8)), "Backend rejects oversized record");
            for (int i = 8; i < 64; i++) require(MemoryUtil.memGetByte(small.address + i) == 0, "Backend did not overrun scratch");
            MemoryUtil.memPutLong(record.address, source.key ^ 1);
            byte[] original = bytes(record);
            backend.setSectionData(source.key, record);
            mustFail(() -> storage.loadSection(source), "Invalid saved section fails load");
            require(Arrays.equals(original, bytes(backend.getSectionData(source.key, scratch.createUntrackedUnfreeableReference()))), "Invalid record preserved");
            mustFail(() -> storage.saveSection(source), "Writes disabled after invalid load");
            mustFail(() -> storage.putIdMapping(19, ByteBuffer.allocateDirect(1).put((byte) 1).flip()), "Mapping writes disabled after invalid load");
            mustFail(() -> storage.loadSection(source), "Loads disabled after invalid load");
            require(!backend.getIdMappingsData().containsKey(19), "Rejected mapping was not written");
            require(Arrays.equals(original, bytes(backend.getSectionData(source.key, scratch.createUntrackedUnfreeableReference()))), "Disabled save did not replace record");
            storage.flush();
        } finally {
            storage.close();
            record.free();
            scratch.free();
            small.free();
        }
    }

    private static byte[] bytes(MemoryBuffer buffer) {
        require(buffer != null, "Expected persisted data");
        byte[] result = new byte[Math.toIntExact(buffer.size)];
        buffer.asByteBuffer().get(result);
        return result;
    }

    private static void mustFail(Runnable action, String description) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(description);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
