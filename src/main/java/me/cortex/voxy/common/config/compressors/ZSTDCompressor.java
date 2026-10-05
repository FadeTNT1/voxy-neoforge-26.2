package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ResizingThreadLocalMemoryBuffer;

import static me.cortex.voxy.common.util.GlobalCleaner.CLEANER;
import static org.lwjgl.util.zstd.Zstd.*;

public class ZSTDCompressor implements StorageCompressor {
    private record Ref(long ptr) {}

    private static Ref createCleanableCompressionContext() {
        long ctx = ZSTD_createCCtx();
        if (ctx == 0) throw new IllegalStateException("Unable to create ZSTD compression context");
        var ref = new Ref(ctx);
        CLEANER.register(ref, ()->ZSTD_freeCCtx(ctx));
        return ref;
    }

    private static Ref createCleanableDecompressionContext() {
        long ctx = ZSTD_createDCtx();
        if (ctx == 0) throw new IllegalStateException("Unable to create ZSTD decompression context");
        var ref = new Ref(ctx);
        CLEANER.register(ref, ()->ZSTD_freeDCtx(ctx));
        return ref;
    }

    private static final ThreadLocal<Ref> COMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableCompressionContext);
    private static final ThreadLocal<Ref> DECOMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableDecompressionContext);

    private static final ResizingThreadLocalMemoryBuffer SCRATCH = new ResizingThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final int level;

    public ZSTDCompressor(int level) {
        this.level = level;
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        if (saveData.size <= 0 || saveData.size > SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE) {
            throw new IllegalArgumentException("Invalid section size for ZSTD compression: " + saveData.size);
        }
        var compressedData = SCRATCH.get(ZSTD_COMPRESSBOUND(saveData.size)).createUntrackedUnfreeableReference();
        long ctx = COMPRESSION_CTX.get().ptr;
        checkResult(nZSTD_CCtx_setParameter(ctx, ZSTD_c_compressionLevel, this.level));
        checkResult(nZSTD_CCtx_setParameter(ctx, ZSTD_c_checksumFlag, 1));
        // compress2 honors checksum parameters; compressCCtx resets advanced parameters.
        long compressedSize = nZSTD_compress2(ctx, compressedData.address, compressedData.size, saveData.address, saveData.size);
        checkResult(compressedSize);
        return compressedData.subSize(compressedSize);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        if (saveData.size <= 0) {
            throw new IllegalArgumentException("Empty ZSTD frame");
        }
        var decompressed = SCRATCH.get().createUntrackedUnfreeableReference();
        long size = nZSTD_decompressDCtx(DECOMPRESSION_CTX.get().ptr, decompressed.address,
                SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE, saveData.address, saveData.size);
        checkResult(size);
        if (size == 0) throw new IllegalArgumentException("Empty ZSTD section");
        return decompressed.subSize(size);
    }

    private static void checkResult(long result) {
        if (ZSTD_isError(result)) {
            throw new IllegalArgumentException("ZSTD failure: " + ZSTD_getErrorName(result));
        }
    }

    @Override
    public void close() {

    }

    public static class Config extends CompressorConfig {
        public int compressionLevel;

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new ZSTDCompressor(this.compressionLevel);
        }

        public static String getConfigTypeName() {
            return "ZSTD";
        }
    }
}
