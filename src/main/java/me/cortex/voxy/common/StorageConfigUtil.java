package me.cortex.voxy.common;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.util.AtomicFiles;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class StorageConfigUtil {

    public static <T> T getCreateStorageConfig(Class<T> clz, Predicate<T> verifier, Supplier<T> defaultConfig, Path path) {
        return getCreateStorageConfig(clz, verifier, defaultConfig, path, new String[0]);
    }

    public static <T> T getCreateStorageConfig(Class<T> clz, Predicate<T> verifier, Supplier<T> defaultConfig, Path path,
                                              String... optionalRootFlags) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var json = path.resolve("config.json");
        // An unreadable existing config is not a missing config.
        if (!Files.notExists(json)) {
            try {
                var tree = readConfigJson(json).getAsJsonObject();
                requireInteger(tree.get("version"));
                // Polymorphic adapters consume TYPE, so validate against a separate tree.
                T config = Serialization.GSON.fromJson(tree.deepCopy(), clz);
                if (config == null || !verifier.test(config)) throw new JsonParseException("Invalid storage configuration");
                validateFields(config, tree, Set.of(optionalRootFlags));
                return config;
            } catch (Exception e) {
                throw new IllegalStateException("Refusing to open storage with invalid config; original preserved: " + json, e);
            }
        }

        try (var entries = Files.list(path)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalStateException("Missing storage config in nonempty directory; refusing to guess backend: " + path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot check storage directory; refusing to create config: " + path, e);
        }
        T config = defaultConfig.get();
        if (config == null || !verifier.test(config)) throw new IllegalStateException("Invalid default storage config");
        try {
            AtomicFiles.writeString(json, Serialization.GSON.toJson(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed write the config, aborting!", e);
        }
        return config;
    }

    static int requireInteger(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("Missing or non-numeric config version");
        }
        return element.getAsBigDecimal().intValueExact();
    }

    /** Gson normally accepts repeated member names; persisted storage choices must be unambiguous. */
    static JsonElement readConfigJson(Path file) throws IOException {
        try (var reader = new JsonReader(new StringReader(Files.readString(file)))) {
            reader.setStrictness(Strictness.STRICT);
            var tree = readUniqueJson(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JsonParseException("Trailing config data");
            return tree;
        }
    }

    private static JsonElement readUniqueJson(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject();
                var names = new HashSet<String>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new JsonParseException("Duplicate config member: " + name);
                    object.add(name, readUniqueJson(reader));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readUniqueJson(reader));
                reader.endArray();
                return array;
            }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new JsonPrimitive(new BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw new JsonParseException("Missing config value");
        }
    }

    // Reference: upstream-2622 Config classes use public fields for the storage graph.
    // Reject absent fields instead of silently using Java constructor defaults.
    private static void validateFields(Object value, JsonElement tree, Set<String> optionalFlags) throws ReflectiveOperationException {
        if (value instanceof Iterable<?> values) {
            var array = tree.getAsJsonArray();
            int index = 0;
            for (var child : values) validateFields(child, array.get(index++), Set.of());
        } else if (tree.isJsonObject()) {
            var object = tree.getAsJsonObject();
            for (var field : value.getClass().getFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                // Explicit root boolean additions may use their constructor defaults;
                // backend fields and the entire nested storage graph remain mandatory.
                if (!object.has(field.getName()) && field.getType() == boolean.class && optionalFlags.contains(field.getName())) continue;
                if (!object.has(field.getName()) || object.get(field.getName()).isJsonNull()) {
                    throw new JsonParseException("Missing config field: " + field.getName());
                }
                field.setAccessible(true);
                Object child = field.get(value);
                if (child == null) throw new JsonParseException("Null config field: " + field.getName());
                var childTree = object.get(field.getName());
                if (child instanceof Integer || child instanceof Long || child instanceof Short || child instanceof Byte) {
                    if (!childTree.isJsonPrimitive() || !childTree.getAsJsonPrimitive().isNumber()) {
                        throw new JsonParseException("Non-numeric config field: " + field.getName());
                    }
                    if (childTree.getAsBigDecimal().compareTo(BigDecimal.valueOf(((Number) child).longValue())) != 0) {
                        throw new JsonParseException("Truncated or overflowing config field: " + field.getName());
                    }
                } else if (child instanceof Boolean && (!childTree.isJsonPrimitive() || !childTree.getAsJsonPrimitive().isBoolean())) {
                    throw new JsonParseException("Non-boolean config field: " + field.getName());
                } else if (child instanceof String && (!childTree.isJsonPrimitive() || !childTree.getAsJsonPrimitive().isString())) {
                    throw new JsonParseException("Non-string config field: " + field.getName());
                }
                validateFields(child, childTree, Set.of());
            }
        }
    }

    public static SectionSerializationStorage.Config createDefaultSerializer() {
        //Create the default config
        var baseDB = new RocksDBStorageBackend.Config();

        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 1;

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;

        return serializer;
    }
}
