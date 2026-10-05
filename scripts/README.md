# Build validation

The active 26.2 validator is `validate_port.py`. Run it directly with Python >=3.11:

```powershell
python scripts/validate_port.py build/libs/voxy-0.2.20-neoforge.1.jar
```

`gradlew build` invokes it automatically along with the Java safety regressions and NeoForge JUnit tests. It verifies the produced JAR's Minecraft/Sodium constraints, Java 25 bytecode, mixin registration/class presence, absence of orphan mixin classes, nested libraries, native library paths, and removed Fabric dependencies.

The other scripts in this directory are retained historical 1.21.1 reference/signature utilities. Their old Minecraft/Sodium paths and assumptions do not validate this 26.2 port, and they are not part of the current build checks. Use the actual 26.2 generated sources and loaded runtime tests described in [TESTING.md](../TESTING.md).
