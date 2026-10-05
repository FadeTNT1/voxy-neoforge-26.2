package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ResizingThreadLocalMemoryBuffer;
import net.jpountz.lz4.LZ4Factory;
import org.lwjgl.system.MemoryUtil;

public class LZ4Compressor implements StorageCompressor {
    private static final ResizingThreadLocalMemoryBuffer SCRATCH = new ResizingThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final net.jpountz.lz4.LZ4Compressor compressor;
    private final net.jpountz.lz4.LZ4SafeDecompressor decompressor;
    public LZ4Compressor() {
        this.decompressor = LZ4Factory.nativeInstance().safeDecompressor();
        this.compressor = LZ4Factory.nativeInstance().fastCompressor();
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        if (saveData.size <= 0 || saveData.size > SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE) {
            throw new IllegalArgumentException("Invalid section size for LZ4 compression: " + saveData.size);
        }
        var res = SCRATCH.get(this.compressor.maxCompressedLength((int) saveData.size)+4).createUntrackedUnfreeableReference();
        MemoryUtil.memPutInt(res.address, (int) saveData.size);
        int size = this.compressor.compress(saveData.asByteBuffer(), 0, (int) saveData.size, res.asByteBuffer(), 4, (int) res.size-4);
        return res.subSize(size+4);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        if (saveData.size <= Integer.BYTES || saveData.size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid LZ4 frame size: " + saveData.size);
        }
        int size = MemoryUtil.memGetInt(saveData.address);
        if (size <= 0 || size > SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE) {
            throw new IllegalArgumentException("Invalid LZ4 section size: " + size);
        }
        var res = SCRATCH.get(size).createUntrackedUnfreeableReference();
        // The safe API bounds both source and destination, including truncated input.
        int actualSize = this.decompressor.decompress(saveData.asByteBuffer(), 4,
                (int) saveData.size - 4, res.asByteBuffer(), 0, size);
        if (actualSize != size) {
            throw new IllegalArgumentException("LZ4 output length differs from section header");
        }
        return res.subSize(size);
    }

    @Override
    public void close() {
    }

    public static class Config extends CompressorConfig {

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new LZ4Compressor();
        }

        public static String getConfigTypeName() {
            return "LZ4";
        }
    }
}
