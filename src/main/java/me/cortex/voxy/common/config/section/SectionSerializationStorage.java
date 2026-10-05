package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

public class SectionSerializationStorage extends SectionStorage {
    public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 32 * 32 * 32 * 8 * 2 + 8;

    private final StorageBackend backend;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    public SectionSerializationStorage(StorageBackend storageBackend) {
        this.backend = storageBackend;
    }

    private static final ThreadLocalMemoryBuffer MEMORY_CACHE = new ThreadLocalMemoryBuffer(BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    public int loadSection(WorldSection into) {
        this.checkHealthy();
        try {
            var data = this.backend.getSectionData(into.key, MEMORY_CACHE.get().createUntrackedUnfreeableReference());
            if (data == null) {
                return 1;
            }
            if (!SaveLoadSystem3.deserialize(into, data)) {
                throw new IllegalStateException("Invalid saved section " + into.key
                        + "; record preserved for recovery. Storage disabled for this session.");
            }
            return 0;
        } catch (RuntimeException e) {
            throw this.disable(e);
        }
    }

    private void checkHealthy() {
        var cause = this.failure.get();
        if (cause != null) {
            throw new IllegalStateException("Storage disabled after a previous failure; preserve the database and restart after recovery.", cause);
        }
    }

    private RuntimeException disable(RuntimeException cause) {
        this.failure.compareAndSet(null, cause);
        return cause;
    }


    @Override
    public void saveSection(WorldSection section) {
        this.checkHealthy();
        try {
            var saveData = SaveLoadSystem3.serialize(section);
            this.checkHealthy();
            this.backend.setSectionData(section.key, saveData);
            //Note that savedData isnt freed (the save system uses a cache)
        } catch (RuntimeException e) {
            throw this.disable(e);
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.checkHealthy();
        try {
            this.backend.putIdMapping(id, data);
        } catch (RuntimeException e) {
            throw this.disable(e);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        this.checkHealthy();
        try {
            return this.backend.getIdMappingsData();
        } catch (RuntimeException e) {
            throw this.disable(e);
        }
    }

    @Override
    public void flush() {
        try {
            this.backend.flush();
        } catch (RuntimeException e) {
            throw this.disable(e);
        }
    }

    @Override
    public void close() {
        this.backend.close();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        this.checkHealthy();
        try {
            this.backend.iteratePositions(level, consumer);
        } catch (RuntimeException e) {
            throw this.disable(e);
        }
    }

    public static class Config extends SectionStorageConfig {
        public StorageConfig storage;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            return new SectionSerializationStorage(this.storage.build(ctx));
        }

        public static String getConfigTypeName() {
            return "Serializer";
        }
    }
}
