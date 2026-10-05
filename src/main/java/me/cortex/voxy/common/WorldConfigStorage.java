package me.cortex.voxy.common;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.cortex.voxy.common.util.AtomicFiles;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

public class WorldConfigStorage<T> {
    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Class<T> configType;
    private final LinkedHashMap<WorldIdentifier, T> worldConfigs = new LinkedHashMap<>();

    private final Gson gson;

    private static class InnerHolder<T> {
        private final LinkedHashMap<WorldIdentifier, T> worldConfigs = new LinkedHashMap<>();
    }

    public WorldConfigStorage(Path file, Class<T> configType) {
        this(file, configType, null);
    }

    public WorldConfigStorage(Path file, Class<T> configType, TypeAdapter<T> adapter) {
        this.file = file;
        this.configType = configType;
        var builder = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .setPrettyPrinting()
                .serializeNulls()
                .excludeFieldsWithModifiers(Modifier.PRIVATE)
                .registerTypeAdapter(WorldIdentifier.class, WorldIdentifier.GsonAdapter.INSTANCE)
                .registerTypeAdapter(InnerHolder.class, new TypeAdapter<InnerHolder<T>>() {
                    @Override
                    public void write(JsonWriter writer, InnerHolder<T> obj) throws IOException {
                        writer.beginObject();

                        writer.name("version");
                        writer.value(FORMAT_VERSION);

                        writer.name("configs");
                        writer.beginArray();
                        for (var entry : obj.worldConfigs.entrySet()) {
                            writer.beginObject();
                            writer.name("worldId");
                            if (entry.getKey() != null) {
                                WorldIdentifier.GsonAdapter.INSTANCE.write(writer, entry.getKey());
                            } else {
                                writer.nullValue();
                            }

                            writer.name("config");
                            if (entry.getValue() != null) {
                                WorldConfigStorage.this.gson.getAdapter(WorldConfigStorage.this.configType)
                                        .write(writer, entry.getValue());
                            } else {
                                writer.nullValue();
                            }
                            writer.endObject();
                        }
                        writer.endArray();

                        writer.endObject();
                    }

                    @Override
                    public InnerHolder<T> read(JsonReader in) throws IOException {
                        var cfg = WorldConfigStorage.this.gson.getAdapter(JsonElement.class).read(in).getAsJsonObject();

                        var ver = cfg.get("version");
                        if (StorageConfigUtil.requireInteger(ver) != FORMAT_VERSION) {
                            throw new JsonParseException("Unsupported world config version: " + ver);
                        }

                        var holder = new InnerHolder<T>();
                        var cfgs = cfg.get("configs");
                        if (cfgs == null || !cfgs.isJsonArray()) throw new JsonParseException("Missing world configs array");
                        for (var objE : cfgs.getAsJsonArray()) {
                            var obj = objE.getAsJsonObject();
                            WorldIdentifier key = null;
                            T val = null;
                            var id = obj.get("worldId");
                            if (id == null) throw new JsonParseException("Missing worldId field");
                            if (!id.isJsonNull()) {
                                var worldId = id.getAsJsonObject();
                                for (String field : new String[]{"key", "dimension"}) {
                                    var value = worldId.get(field);
                                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                                        throw new JsonParseException("Missing or invalid worldId " + field);
                                    }
                                }
                                var seed = worldId.get("biomeSeed");
                                if (seed == null || !seed.isJsonPrimitive() || !seed.getAsJsonPrimitive().isNumber()) {
                                    throw new JsonParseException("Missing or invalid worldId biomeSeed");
                                }
                                seed.getAsBigDecimal().longValueExact();
                                key = WorldIdentifier.GsonAdapter.INSTANCE.fromJsonTree(id);
                            }
                            var valTree = obj.get("config");
                            if (valTree == null) throw new JsonParseException("Missing config field");
                            if (!valTree.isJsonNull()) {
                                val = WorldConfigStorage.this.gson.getAdapter(WorldConfigStorage.this.configType).fromJsonTree(valTree);
                            }

                            if (holder.worldConfigs.containsKey(key)) throw new JsonParseException("Duplicate worldId: " + key);
                            holder.worldConfigs.put(key, val);
                        }

                        return holder;
                    }
                });

        if (adapter != null) {
            builder.registerTypeAdapter(configType, adapter);
        }
        this.gson = builder.create();

        this.load();
    }

    private void load() {
        if (!Files.notExists(this.file)) {
            try {
                var conf = this.gson.fromJson(StorageConfigUtil.readConfigJson(this.file), InnerHolder.class);
                if (conf == null) throw new JsonParseException("Null world config");
                this.worldConfigs.clear();
                this.worldConfigs.putAll(conf.worldConfigs);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Refusing to load invalid world config; original preserved: " + this.file, e);
            }
        }
    }

    public T getOrCreate(WorldIdentifier id, Supplier<T> provider) {
        if (this.worldConfigs.containsKey(id)) {
            return this.worldConfigs.get(id);
        }
        var val = provider.get();
        this.worldConfigs.put(id, val);

        this.save();
        return val;
    }

    public T getNullable(WorldIdentifier id) {
        return this.worldConfigs.getOrDefault(id, null);
    }

    public void put(WorldIdentifier id, T obj) {
        this.worldConfigs.put(id, obj);

        this.save();
    }

    public void remove(WorldIdentifier id) {
        this.worldConfigs.remove(id);

        this.save();
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            var holder = new InnerHolder<T>();
            holder.worldConfigs.putAll(this.worldConfigs);
            AtomicFiles.writeString(this.file, this.gson.toJson(holder));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }
}
