package me.cortex.voxy;

import org.junit.jupiter.api.Test;

/** Runs with ModDevGradle's supported NeoForge JUnit loader. */
public class MapperRegistryTest {
    @Test
    void mappingsSurviveMissingModsAndPersistenceFailures() throws Exception {
        MapperPersistenceTest.runChecks();
    }
}
