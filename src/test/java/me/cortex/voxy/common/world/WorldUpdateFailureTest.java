package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

class WorldUpdateFailureTest {
    @Test
    void failedParentLoadReleasesChildAndAllowsWorldShutdown() {
        var storage = new SectionStorage() {
            public int loadSection(WorldSection section) {
                if (section.lvl == 1) throw new IllegalStateException("Injected parent load failure");
                return 1;
            }
            public void saveSection(WorldSection section) { }
            public void putIdMapping(int id, ByteBuffer data) { }
            public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(); }
            public void flush() { }
            public void close() { }
            public void iteratePositions(int level, LongConsumer consumer) { }
        };
        var engine = new WorldEngine(storage);
        engine.setSaveCallback((world, section, nonBlocking, acquired) -> {
            section.setNotDirty();
            return false;
        });
        var input = VoxelizedSection.createEmpty();
        input.section[0] = Mapper.withBlockBiome(0, 1, 0);
        input.lvl0NonAirCount = 1;
        assertEquals("Injected parent load failure", assertThrows(IllegalStateException.class,
                () -> WorldUpdater.insertUpdate(engine, input)).getMessage());
        assertEquals(0, engine.getActiveSectionCount(), "Failed parent load leaked the acquired child");
        assertDoesNotThrow(engine::free);
    }
}
