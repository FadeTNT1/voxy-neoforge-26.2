package me.cortex.voxy.common.world.other;

import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;


//There are independent mappings for biome and block states, these get combined in the shader and allow for more
// variaty of things
public class Mapper {
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;

    private final IMappingStorage storage;
    public static final long UNKNOWN_MAPPING = -1;
    public static final long AIR = 0;

    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<StateEntry> blockId2stateEntry = new ObjectArrayList<>();
    private volatile StateEntry[] publishedBlockEntries;


    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<BiomeEntry> biomeId2biomeEntry = new ObjectArrayList<>();

    private Consumer<StateEntry> newStateCallback;
    private Consumer<BiomeEntry> newBiomeCallback;
    public Mapper(IMappingStorage storage) {
        this.storage = storage;
        //Insert air since its a special entry (index 0)
        var airEntry = new StateEntry(0, Blocks.AIR.defaultBlockState());
        this.block2stateEntry.put(airEntry.state, airEntry);
        this.blockId2stateEntry.add(airEntry);

        this.loadFromStorage();
        this.publishedBlockEntries = this.blockId2stateEntry.toArray(StateEntry[]::new);
    }


    public static boolean isAir(long id) {
        //Note: air can mean void, cave or normal air, as the block state is remapped during ingesting
        return (id&(((1L<<20)-1)<<27)) == 0;
    }

    public static int isNotAirInt(long id) {
        //This is stupid and insane that even have todo this
        // works cause 0 is air, so !=0 is not air
        return Math.min(getBlockId(id), 1);
    }

    public static int getBlockId(long id) {
        return (int) ((id>>27)&((1<<20)-1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id>>47)&0x1FF);
    }

    public static int getLightId(long id) {
        return (int) ((id>>56)&0xFF);
    }

    public static long withLight(long id, int light) {
        return (id&(~(0xFFL<<56)))|(Integer.toUnsignedLong(light&0xFF)<<56);
    }

    public static long withBlockBiome(long id, int block, int biome) {
        return (id&(0xFFL<<56))|(Integer.toUnsignedLong(block)<<27)|(Integer.toUnsignedLong(biome)<<47);
    }

    public static long airWithLight(int light) {
        return Integer.toUnsignedLong(light&0xFF)<<56;
    }

    public void setStateCallback(Consumer<StateEntry> stateCallback) {
        this.newStateCallback = stateCallback;
    }

    public void setBiomeCallback(Consumer<BiomeEntry> biomeCallback) {
        this.newBiomeCallback = biomeCallback;
    }

    private void loadFromStorage() {
        //TODO: FIXME: have/store the minecraft version the mappings are from (the data version)
        // SharedConstants.getGameVersion().dataVersion().id()
        // then use this to create an update path instead

        var mappings = this.storage.getIdMappingsData();
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();

        boolean[] forceResave = new boolean[1];
        for (var entry : mappings.int2ObjectEntrySet()) {
            int entryType = entry.getIntKey()>>>30;
            int id = entry.getIntKey() & ((1<<30)-1);
            if (entryType == BLOCK_STATE_TYPE) {
                if (id == 0 || id >= (1 << 20)) throw new IllegalStateException("Invalid stored block ID: " + id);
                var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                sentries.add(sentry);
                // Unresolved IDs remain in the array, but their display fallback must never own a reverse mapping.
                if (sentry.state.isAir()) continue;
                var oldEntry = this.block2stateEntry.get(sentry.state);
                if (oldEntry != null) {
                    Logger.warn("Multiple stored mappings for blockstate; retaining both IDs: " + oldEntry.id + ":" + sentry.id + ":" + sentry.state);
                }
                this.block2stateEntry.merge(sentry.state, sentry, (old, added) -> old.id < added.id ? old : added);
            } else if (entryType == BIOME_TYPE) {
                if (id >= (1 << 9)) throw new IllegalStateException("Invalid stored biome ID: " + id);
                var bentry = BiomeEntry.deserialize(id, entry.getValue());
                bentries.add(bentry);
                if (bentry.unresolved) continue;
                this.biome2biomeEntry.merge(bentry.biome, bentry, (old, added) -> old.id < added.id ? old : added);
            } else {
                throw new IllegalStateException("Unknown entryType");
            }
        }

        //Insert into the arrays
        sentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            if (this.blockId2stateEntry.size() != entry.id) {
                throw new IllegalStateException("Block entry not ordered");
            }
            this.blockId2stateEntry.add(entry);
        });

        bentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            if (this.biomeId2biomeEntry.size() != entry.id) {
                throw new IllegalStateException("Biome entry not ordered. got " + entry.biome + " with id " + entry.id + " expected id " + this.biomeId2biomeEntry.size());
            }
            this.biomeId2biomeEntry.add(entry);
        });

        // Never migrate storage during load: source data versions are unknown, and loaded bytes are retained.
    }

    public final int getBlockStateCount() {
        return this.publishedBlockEntries.length;
    }

    private StateEntry registerNewBlockState(BlockState state) {
        StateEntry entry;
        this.blockLock.lock();
        try {
            entry = this.block2stateEntry.get(state);
            if (entry != null) return entry;
            if (this.blockId2stateEntry.size() >= (1 << 20)) throw new IllegalStateException("Block mapping ID space exhausted");
            entry = new StateEntry(this.blockId2stateEntry.size(), state);
            this.persistMapping(entry.id | (BLOCK_STATE_TYPE << 30), entry.serialize());
            // A section must never observe an ID before its mapping is durable.
            this.storage.flush();
            this.blockId2stateEntry.add(entry);
            // ponytail: copy only on new IDs; use chunked snapshots if mapping allocation becomes significant.
            this.publishedBlockEntries = this.blockId2stateEntry.toArray(StateEntry[]::new);
            this.block2stateEntry.put(state, entry);
        } finally { this.blockLock.unlock(); }
        if (this.newStateCallback != null) this.newStateCallback.accept(entry);
        return entry;
    }

    private BiomeEntry registerNewBiome(String biome) {
        BiomeEntry entry;
        this.biomeLock.lock();
        try {
            entry = this.biome2biomeEntry.get(biome);
            if (entry != null) return entry;
            if (this.biomeId2biomeEntry.size() >= (1 << 9)) throw new IllegalStateException("Biome mapping ID space exhausted");
            entry = new BiomeEntry(this.biomeId2biomeEntry.size(), biome);
            this.persistMapping(entry.id | (BIOME_TYPE << 30), entry.serialize());
            this.storage.flush();
            this.biomeId2biomeEntry.add(entry);
            this.biome2biomeEntry.put(biome, entry);
        } finally { this.biomeLock.unlock(); }
        if (this.newBiomeCallback != null) this.newBiomeCallback.accept(entry);
        return entry;
    }

    private void persistMapping(int id, byte[] serialized) {
        ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
        try {
            buffer.put(serialized).flip();
            this.storage.putIdMapping(id, buffer);
        } finally { MemoryUtil.memFree(buffer); }
    }


    //TODO:FIXME: IS VERY SLOW NEED TO MAKE IT LOCK FREE, or at minimum use a concurrent map
    public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
        if (state.isAir()) return Byte.toUnsignedLong(light) <<56;//Special case and fast return for air, dont care about the biome
        return composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
    }

    public BlockState getBlockStateFromBlockId(int blockId) {
        return this.publishedBlockEntries[blockId].state;
    }

    public int getIdForBlockState(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        var mapping = this.block2stateEntry.get(state);
        if (mapping == null) {
            mapping = this.registerNewBlockState(state);
        }
        return mapping.id;
    }

    public int getBlockStateOpacity(long mappingId) {
        return this.getBlockStateOpacity(getBlockId(mappingId));
    }

    public int getBlockStateOpacity(int blockId) {
        return this.publishedBlockEntries[blockId].opacity;
    }

    public int getIdForBiome(Holder<Biome> biome) {
        String biomeId = biome.unwrapKey().get().identifier().toString();
        var entry = this.biome2biomeEntry.get(biomeId);
        if (entry == null) {
            entry = this.registerNewBiome(biomeId);
        }
        return entry.id;
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId == AIR) {//Dont care about biome for air
            return Byte.toUnsignedLong(light)<<56;
        }
        return (Byte.toUnsignedLong(light)<<56)|(Integer.toUnsignedLong(biomeId) << 47)|(Integer.toUnsignedLong(blockId)<<27);
    }

    public StateEntry[] getStateEntries() {
        return this.publishedBlockEntries.clone();
    }

    public BiomeEntry[] getBiomeEntries() {
        this.biomeLock.lock();
        try { return this.biomeId2biomeEntry.toArray(BiomeEntry[]::new); }
        finally { this.biomeLock.unlock(); }
    }

    public void forceResaveStates() {
        var blocks = this.getStateEntries();
        var biomes = this.getBiomeEntries();


        for (var entry : blocks) {
            if (entry.state.isAir() && entry.id == 0) {
                continue;
            }
            this.persistMapping(entry.id | (BLOCK_STATE_TYPE << 30), entry.serialize());
        }

        for (var entry : biomes) {
            this.persistMapping(entry.id | (BIOME_TYPE << 30), entry.serialize());
        }

        this.storage.flush();
    }

    public void close() {

    }


    public static final class StateEntry {
        public final int id;
        public final BlockState state;
        public final int opacity;
        private final byte[] storedData;
        public StateEntry(int id, BlockState state) {
            this(id, state, null);
        }

        private StateEntry(int id, BlockState state, byte[] storedData) {
            this.id = id;
            this.state = state;
            this.storedData = storedData == null ? null : storedData.clone();
            //Override opacity of leaves to be solid
            if (state.getBlock() instanceof LeavesBlock) {
                this.opacity = 15;
            } else {
                this.opacity = state.getLightDampening();
            }
        }

        public byte[] serialize() {
            if (this.storedData != null) return this.storedData.clone();
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, this.state).result().get());
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(1L << 20));
                if (compound.getIntOr("id", -1) != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                var bsc = compound.getCompound("block_state").orElseThrow();
                var state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                if (state.isError()) {
                    Logger.info("Could not decode blockstate, attempting fixes, error: "+ state.error().get().message());
                    bsc = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE,bsc),0, SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
                    state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                    if (state.isError()) {
                        Logger.error("Could not decode blockstate; retaining stored bytes and ID, displaying air. id:" + id + " error: " + state.error().get().message());
                        return new StateEntry(id, Blocks.AIR.defaultBlockState(), data);
                    } else {
                        Logger.info("Fixed blockstate to: " + state.getOrThrow());
                        forceResave[0] |= true;
                        return checkedState(id, state.getOrThrow(), bsc, data);
                    }
                } else {
                    return checkedState(id, state.getOrThrow(), bsc, data);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static StateEntry checkedState(int id, BlockState state, CompoundTag encoded, byte[] data) {
            // MC 26.2 StateHolder.CODEC uses Name/Properties and silently defaults invalid Properties.
            // Reject that lossy decode for reverse lookup, and always preserve the original compressed bytes.
            boolean valid = true;
            if (encoded.contains("Properties")) {
                var properties = encoded.getCompound("Properties");
                valid = properties.isPresent();
                if (valid) {
                    var canonical = (CompoundTag) BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
                    var canonicalProperties = canonical.getCompound("Properties").orElseGet(CompoundTag::new);
                    for (String name : properties.get().keySet()) {
                        var value = properties.get().getString(name);
                        if (value.isEmpty() || !value.equals(canonicalProperties.getString(name))) {
                            valid = false;
                            break;
                        }
                    }
                }
            }
            if (!valid || state.isAir()) {
                Logger.warn("Unresolved stored blockstate; retaining bytes and ID, displaying air. id:" + id);
                state = Blocks.AIR.defaultBlockState();
            }
            return new StateEntry(id, state, data);
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;
        private final byte[] storedData;
        private final boolean unresolved;

        public BiomeEntry(int id, String biome) {
            this(id, biome, null, false);
        }

        private BiomeEntry(int id, String biome, byte[] storedData, boolean unresolved) {
            this.id = id;
            this.biome = biome;
            this.storedData = storedData == null ? null : storedData.clone();
            this.unresolved = unresolved;
        }

        public byte[] serialize() {
            if (this.storedData != null) return this.storedData.clone();
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.putString("biome_id", this.biome);
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static BiomeEntry deserialize(int id, byte[] data) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(1L << 20));
                if (compound.getIntOr("id", -1) != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                String biome = compound.getStringOr("biome_id", null);
                boolean unresolved = biome == null || Identifier.tryParse(biome) == null;
                if (unresolved) {
                    Logger.warn("Malformed stored biome; retaining bytes and ID, displaying plains. id:" + id);
                    biome = "minecraft:plains";
                }
                return new BiomeEntry(id, biome, data, unresolved);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
