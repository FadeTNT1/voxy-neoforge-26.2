"""Validate the distributable, not just sources. Python 3.11+, no dependencies."""
import json
import sys
import tomllib
import zipfile
from pathlib import Path


def validate(path):
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        metadata = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())
        dependencies = {dep["modId"]: dep for dep in metadata["dependencies"]["voxy"]}
        assert dependencies["minecraft"]["versionRange"] == "[26.2]", "Wrong Minecraft target"
        assert dependencies["sodium"]["versionRange"] == "[0.9.2+mc26.2]", "Wrong Sodium target"
        assert "fabric_api" not in dependencies, "Fabric API must not be required"
        assert "fabric.mod.json" not in names and "voxy.accesswidener" not in names
        count = 0
        registered = set()
        for entry in metadata["mixins"]:
            config = json.loads(jar.read(entry["config"]))
            assert config["compatibilityLevel"] == "JAVA_25"
            plugin = config["plugin"].replace(".", "/") + ".class"
            assert plugin in names, f"Missing conditional mixin plugin: {plugin}"
            for side in ("mixins", "client", "server"):
                mixins = config.get(side, [])
                assert len(set(mixins)) == len(mixins), "Duplicate mixin registration"
                for mixin in mixins:
                    class_path = (config["package"] + "." + mixin).replace(".", "/") + ".class"
                    assert class_path in names, f"Mixin absent from JAR: {class_path}"
                    registered.add(class_path)
                    count += 1
        for name in names:
            if name.startswith("me/cortex/voxy/") and name.endswith(".class"):
                bytecode = jar.read(name)
                assert b"net/fabricmc/" not in bytecode, f"Fabric API leaked into {name}"
                assert b"toni/sodiumoptionsapi/" not in bytecode, f"Obsolete config API in {name}"
                if b"Lorg/spongepowered/asm/mixin/Mixin;" in bytecode:
                    assert name in registered, f"Compiled mixin not registered: {name}"
                assert bytecode[6:8] == bytes([0, 69]), f"Wrong Java 25 bytecode target in {name}"
        nested = json.loads(jar.read("META-INF/jarjar/metadata.json"))["jars"]
        artifacts = {entry["identifier"]["artifact"] for entry in nested}
        required = {"lwjgl-lmdb", "lwjgl-zstd", "rocksdbjni", "jedis", "commons-pool2", "lz4-java", "xz", "sqlite-jdbc"}
        assert required <= artifacts, f"Missing bundled libraries: {required - artifacts}"
        for entry in nested:
            assert entry["path"] in names, f"Missing nested JAR: {entry['path']}"
        for platform, extension, prefix in (("windows/x64", "dll", ""), ("linux/x64", "so", "lib"), ("linux/arm64", "so", "lib")):
            for module in ("lmdb", "zstd"):
                native = f"{platform}/org/lwjgl/{module}/{prefix}lwjgl_{module}.{extension}"
                assert native in names, f"Missing native: {native}"
        assert "me/cortex/voxy/client/config/VoxyConfigMenu.class" in names
        assert "me/cortex/voxy/client/core/IrisVoxyRenderPipeline.class" in names
        print(f"Validated {path.name}: {count} mixins, {len(nested)} bundled libraries, native binaries, no Fabric API leakage.")


if __name__ == "__main__":
    validate(Path(sys.argv[1]))
