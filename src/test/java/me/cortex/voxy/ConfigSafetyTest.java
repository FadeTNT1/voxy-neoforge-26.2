package me.cortex.voxy;

import com.google.gson.Gson;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.WorldConfigStorage;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.util.AtomicFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Standalone regression checks; no mod loader or user saves are needed. */
public final class ConfigSafetyTest {
    public static class Config {
        public int version = 1;
        public String sectionStorageConfig = "custom-storage";
    }

    public static class CurrentConfig extends Config {
        public boolean disabled = false;
    }

    public static class NestedConfig {
        public int version = 1;
        public Child sectionStorageConfig = new Child();
    }

    public static class Child {
        public String path = "custom";
        public int count = 2;
    }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory(Path.of("build"), "config-safety-");
        Serialization.GSON = new Gson();
        var validDirectory = Files.createDirectory(root.resolve("valid-storage"));
        var validFile = validDirectory.resolve("config.json");
        String valid = "{\"version\":1,\"sectionStorageConfig\":\"custom\",\"extra\":true}";
        Files.writeString(validFile, valid);
        var loaded = StorageConfigUtil.getCreateStorageConfig(Config.class,
                c -> c.version == 1 && c.sectionStorageConfig != null, Config::new, validDirectory);
        require(loaded.sectionStorageConfig.equals("custom"), "Existing custom storage loaded");
        require(Files.readString(validFile).equals(valid), "Valid existing config is not rewritten");
        var compatible = StorageConfigUtil.getCreateStorageConfig(CurrentConfig.class,
                c -> c.version == 1 && c.sectionStorageConfig != null, CurrentConfig::new, validDirectory, "disabled");
        require(!compatible.disabled && compatible.sectionStorageConfig.equals("custom"), "Original config uses safe new flag default");
        require(Files.readString(validFile).equals(valid), "Original config bytes stay unchanged");
        for (String flag : new String[]{"null", "\"false\"", "0"}) {
            String invalid = "{\"version\":1,\"sectionStorageConfig\":\"custom\",\"disabled\":" + flag + "}";
            Files.writeString(validFile, invalid);
            requireRejected(() -> StorageConfigUtil.getCreateStorageConfig(CurrentConfig.class,
                    c -> c.version == 1 && c.sectionStorageConfig != null, CurrentConfig::new, validDirectory, "disabled"));
            require(Files.readString(validFile).equals(invalid), "Invalid optional root flag preserved");
        }
        var missingConfigDirectory = Files.createDirectory(root.resolve("missing-storage-config"));
        var existingData = missingConfigDirectory.resolve("existing-data");
        Files.writeString(existingData, "preserve backend data");
        requireRejected(() -> StorageConfigUtil.getCreateStorageConfig(Config.class,
                c -> c.version == 1, Config::new, missingConfigDirectory));
        require(Files.notExists(missingConfigDirectory.resolve("config.json")), "No guessed config for existing storage");
        require(Files.readString(existingData).equals("preserve backend data"), "Storage without descriptor preserved");
        for (String invalid : new String[]{"{broken", "null", "{}", "{\"version\":2,\"sectionStorageConfig\":\"custom\"}",
                "{\"version\":1,\"version\":2,\"sectionStorageConfig\":\"custom\"}",
                "{\"version\":1}", "{\"version\":1.5,\"sectionStorageConfig\":\"custom\"}"}) {
            var directory = Files.createTempDirectory(root, "storage-");
            var file = directory.resolve("config.json");
            Files.writeString(file, invalid);
            byte[] before = Files.readAllBytes(file);
            requireRejected(() -> StorageConfigUtil.getCreateStorageConfig(Config.class,
                    c -> c.version == 1 && c.sectionStorageConfig != null, Config::new, directory));
            require(Arrays.equals(before, Files.readAllBytes(file)), "Invalid storage file preserved");
        }
        for (String child : new String[]{"{}", "{\"path\":\"custom\"}", "{\"path\":null,\"count\":2}",
                "{\"path\":\"custom\",\"count\":2.5}", "{\"path\":\"custom\",\"count\":4294967298}"}) {
            var directory = Files.createTempDirectory(root, "nested-");
            var file = directory.resolve("config.json");
            String invalid = "{\"version\":1,\"sectionStorageConfig\":" + child + "}";
            Files.writeString(file, invalid);
            requireRejected(() -> StorageConfigUtil.getCreateStorageConfig(NestedConfig.class,
                    c -> c.version == 1 && c.sectionStorageConfig != null, NestedConfig::new, directory, "disabled", "path", "count"));
            require(Files.readString(file).equals(invalid), "Invalid nested storage file preserved");
        }
        String worldId = "{\"key\":\"minecraft:overworld\",\"biomeSeed\":7,\"dimension\":\"minecraft:overworld\"}";
        String worldEntry = "{\"worldId\":" + worldId + ",\"config\":{}}";
        for (String invalid : new String[]{"{broken", "null", "{}", "{\"version\":2,\"configs\":[]}",
                "{\"version\":1}", "{\"version\":1.5,\"configs\":[]}",
                "{\"version\":1,\"configs\":[{\"worldId\":null,\"config\":{}},{\"worldId\":null,\"config\":{}}]}",
                "{\"version\":1,\"configs\":[{\"worldId\":null}]}",
                "{\"version\":1,\"version\":1,\"configs\":[]}",
                "{\"version\":1,\"configs\":[" + worldEntry + "," + worldEntry + "]}",
                "{\"version\":1,\"configs\":[" + worldEntry.replace("\"biomeSeed\":7", "\"biomeSeed\":7.5") + "]}"}) {
            var file = Files.createTempFile(root, "world-", ".json");
            Files.writeString(file, invalid);
            byte[] before = Files.readAllBytes(file);
            requireRejected(() -> new WorldConfigStorage<>(file, Config.class));
            require(Arrays.equals(before, Files.readAllBytes(file)), "Invalid world file preserved");
        }
        var newDirectory = root.resolve("new-storage");
        StorageConfigUtil.getCreateStorageConfig(Config.class, c -> c.version == 1,
                Config::new, newDirectory);
        require(Serialization.GSON.fromJson(Files.readString(newDirectory.resolve("config.json")), Config.class)
                .sectionStorageConfig.equals("custom-storage"), "New storage config created");
        var validWorld = root.resolve("valid-world.json");
        Files.writeString(validWorld, "{\"version\":1,\"configs\":[{\"worldId\":null,\"config\":{\"version\":1,\"section_storage_config\":\"custom\"}}]}");
        require(new WorldConfigStorage<>(validWorld, Config.class).getNullable(null)
                .sectionStorageConfig.equals("custom"), "Valid world config loaded");
        Files.writeString(validWorld, "{\"version\":1,\"configs\":[{\"worldId\":null,\"config\":null}]}");
        require(new WorldConfigStorage<>(validWorld, Config.class).getOrCreate(null,
                () -> { throw new AssertionError("Existing explicit null config must not be replaced"); }) == null,
                "Explicit null world config remains null");
        Files.writeString(validWorld, "{\"version\":1,\"configs\":[" + worldEntry + "]}");
        new WorldConfigStorage<>(validWorld, Config.class);
        new WorldConfigStorage<>(root.resolve("missing-world.json"), Config.class);
        var atomicFile = root.resolve("atomic.json");
        AtomicFiles.writeString(atomicFile, "original");
        AtomicFiles.writeString(atomicFile, "replacement ✓");
        require(Files.readString(atomicFile).equals("replacement ✓"), "Atomic UTF-8 replacement");
        var nonFile = Files.createDirectory(root.resolve("not-a-file"));
        Files.writeString(nonFile.resolve("marker"), "preserved");
        try {
            AtomicFiles.writeString(nonFile, "must fail");
            throw new AssertionError("Expected non-regular target rejection");
        } catch (java.io.IOException expected) {}
        require(Files.readString(nonFile.resolve("marker")).equals("preserved"), "Failed atomic write preserves target");
        try (var files = Files.list(root)) {
            require(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")), "Atomic temp files cleaned");
        }
        System.out.println("Config safety passed: existing files preserved; invalid configs rejected; valid reads, creation and atomic writes.");
    }

    private static void requireRejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected unsafe existing config to be rejected");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
