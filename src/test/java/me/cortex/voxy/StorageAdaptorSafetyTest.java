package me.cortex.voxy;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor;
import me.cortex.voxy.common.config.storage.other.ReadonlyCachingLayer;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

public final class StorageAdaptorSafetyTest {
    public static void main(String[] args) {
        var base = new MappingBackend();
        var cache = new MappingBackend();
        base.putIdMapping(1, ByteBuffer.wrap(new byte[]{1}));
        cache.putIdMapping(2, ByteBuffer.wrap(new byte[]{2}));
        var layer = new ReadonlyCachingLayer(cache, base);
        if (layer.getIdMappingsData().size() != 2) throw new AssertionError("Cached IDs were discarded");
        layer.flush();
        if (cache.closes != 0 || base.closes != 0 || cache.flushes != 1 || base.flushes != 1)
            throw new AssertionError("Flush closed a database");
        cache.putIdMapping(1, ByteBuffer.wrap(new byte[]{9}));
        rejects(layer::getIdMappingsData);
        rejects(() -> new FragmentedStorageBackendAdaptor(base, cache).getIdMappingsData());
        rejects(() -> new FragmentedStorageBackendAdaptor());
        var empty = new MappingBackend();
        rejects(() -> new FragmentedStorageBackendAdaptor(base, empty).getIdMappingsData());
        System.out.println("Storage adaptor checks passed: cached IDs retained, flush stays open, conflicting fragments rejected.");
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Unsafe mapping configuration was accepted");
    }

    private static final class MappingBackend extends StorageBackend {
        private final Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();
        private int closes;
        private int flushes;
        public void putIdMapping(int id, ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.duplicate().get(bytes);
            mappings.put(id, bytes);
        }
        public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(mappings); }
        public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) { return null; }
        public void setSectionData(long key, MemoryBuffer data) { throw new UnsupportedOperationException(); }
        public void deleteSectionData(long key) { throw new UnsupportedOperationException(); }
        public void iteratePositions(int level, LongConsumer consumer) { throw new UnsupportedOperationException(); }
        public void flush() { flushes++; }
        public void close() { closes++; }
    }
}
