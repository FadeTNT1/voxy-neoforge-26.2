package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge config integration for Voxy.
 * Provides a built-in config screen accessible from the Mods menu.
 *
 * This wraps the existing VoxyConfig and syncs values between the two systems.
 */
@EventBusSubscriber(modid = "voxy", value = net.neoforged.api.distmarker.Dist.CLIENT)
public class VoxyNeoForgeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // General settings
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable Voxy LOD rendering system")
            .define("enabled", VoxyConfig.CONFIG.enabled);

    private static final ModConfigSpec.BooleanValue ENABLE_RENDERING = BUILDER
            .comment("Enable LOD terrain rendering (can be disabled while keeping data ingestion)")
            .define("enableRendering", VoxyConfig.CONFIG.enableRendering);

    private static final ModConfigSpec.BooleanValue INGEST_ENABLED = BUILDER
            .comment("Enable automatic chunk data ingestion for LOD generation")
            .define("ingestEnabled", VoxyConfig.CONFIG.ingestEnabled);

    // Performance settings
    private static final ModConfigSpec.DoubleValue SECTION_RENDER_DISTANCE = BUILDER
            .comment("LOD section render distance (multiplied by 32 for actual chunk distance)",
                     "Example: 16 = 512 chunks render distance")
            .defineInRange("sectionRenderDistance", (double)VoxyConfig.CONFIG.sectionRenderDistance, 0.625, 64.0);

    private static final ModConfigSpec.IntValue SERVICE_THREADS = BUILDER
            .comment("Number of background threads for LOD processing",
                     "Default is based on CPU core count.")
            .defineInRange("serviceThreads", VoxyConfig.CONFIG.serviceThreads, 1, CpuLayout.getCoreCount());

    private static final ModConfigSpec.DoubleValue SUB_DIVISION_SIZE = BUILDER
            .comment("Subdivision size for LOD rendering (28-256)",
                     "Lower = more detailed LODs but more GPU load")
            .defineInRange("subDivisionSize", (double)VoxyConfig.CONFIG.subDivisionSize, 28.0, 256.0);

    // Visual settings
    private static final ModConfigSpec.BooleanValue USE_ENVIRONMENTAL_FOG = BUILDER
            .comment("Apply environmental fog to LOD terrain")
            .define("useEnvironmentalFog", VoxyConfig.CONFIG.getFogMode().hasFog);

    // Advanced settings
    private static final ModConfigSpec.BooleanValue DONT_USE_SODIUM_BUILDER_THREADS = BUILDER
            .comment("Don't share threads with Sodium's chunk builder")
            .define("dontUseSodiumBuilderThreads", VoxyConfig.CONFIG.dontUseSodiumBuilderThreads);

    // LOD boundary buffer (overdraw/overlap)
    private static final ModConfigSpec.IntValue LOD_BOUNDARY_BUFFER = BUILDER
            .comment("LOD boundary overlap in blocks (like DH's overdraw prevention)",
                     "Controls how much LODs overlap with vanilla chunk edges.",
                     "Higher values = more overlap = smoother transitions but slight overdraw.",
                     "0 = exact match (may have gaps), 1 = minimal overlap, 2-4 = smoother for fast flight")
            .defineInRange("lodBoundaryBuffer", VoxyConfig.CONFIG.lodBoundaryBuffer, 0, 4);

    // World curvature (experimental)
    private static final ModConfigSpec.IntValue EARTH_CURVE_RATIO = BUILDER
            .comment("World curvature effect - simulates standing on a spherical planet",
                     "0 = disabled (flat world)",
                     "Higher values = more extreme curvature (smaller planet effect)",
                     "Valid range: 0 (off), or 50-5000. Values 1-49 are auto-corrected to 50.",
                     "Inspired by Distant Horizons' earth curvature feature")
            .defineInRange("earthCurveRatio", VoxyConfig.CONFIG.earthCurveRatio, 0, 5000);

    // Debug settings
    private static final ModConfigSpec.BooleanValue RENDER_STATISTICS = BUILDER
            .comment("Show render statistics in F3 debug screen",
                     "Displays LOD traversal counts, visible sections, and quad counts")
            .define("renderStatistics", false);

    private static final ModConfigSpec.EnumValue<me.cortex.voxy.client.core.NormalRenderPipeline.FogMode> FOG_MODE = BUILDER
            .defineEnum("fogMode", VoxyConfig.CONFIG.getFogMode());
    private static final ModConfigSpec.EnumValue<me.cortex.voxy.client.core.SSAO.SSAOMode> SSAO_MODE = BUILDER
            .defineEnum("ssaoMode", VoxyConfig.CONFIG.getSSAOMode());
    private static boolean syncing;

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Register the config with NeoForge.
     * Call this during mod construction.
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "voxy-client.toml");
    }

    /**
     * Sync NeoForge config values to VoxyConfig.
     */
    private static void syncToVoxyConfig() {
        VoxyConfig.CONFIG.enabled = ENABLED.get();
        VoxyConfig.CONFIG.enableRendering = ENABLE_RENDERING.get();
        VoxyConfig.CONFIG.ingestEnabled = INGEST_ENABLED.get();
        VoxyConfig.CONFIG.sectionRenderDistance = SECTION_RENDER_DISTANCE.get().floatValue();
        VoxyConfig.CONFIG.serviceThreads = SERVICE_THREADS.get();
        VoxyConfig.CONFIG.subDivisionSize = SUB_DIVISION_SIZE.get().floatValue();
        VoxyConfig.CONFIG.useEnvironmentalFog = USE_ENVIRONMENTAL_FOG.get();
        VoxyConfig.CONFIG.setFogMode(!USE_ENVIRONMENTAL_FOG.get() && FOG_MODE.get().hasFog
                ? me.cortex.voxy.client.core.NormalRenderPipeline.FogMode.OFF : FOG_MODE.get());
        VoxyConfig.CONFIG.setSSAOMode(SSAO_MODE.get());
        VoxyConfig.CONFIG.dontUseSodiumBuilderThreads = DONT_USE_SODIUM_BUILDER_THREADS.get();
        VoxyConfig.CONFIG.lodBoundaryBuffer = LOD_BOUNDARY_BUFFER.get();
        VoxyConfig.CONFIG.earthCurveRatio = EARTH_CURVE_RATIO.get();
        if (VoxyConfig.CONFIG.earthCurveRatio > 0 && VoxyConfig.CONFIG.earthCurveRatio < 50) {
            VoxyConfig.CONFIG.earthCurveRatio = 50;
        }

        // RenderStatistics is a runtime-only setting (not saved to JSON)
        RenderStatistics.enabled = RENDER_STATISTICS.get();

        // Also save to the JSON config for compatibility
        syncing = true;
        try { VoxyConfig.CONFIG.save(); }
        finally { syncing = false; }
    }

    /** Keep the Mods screen and Sodium screen on the same persisted values. */
    public static void saveFrom(VoxyConfig config) {
        if (syncing || SPEC == null || !SPEC.isLoaded()) return;
        syncing = true;
        try {
            ENABLED.set(config.enabled);
            ENABLE_RENDERING.set(config.enableRendering);
            INGEST_ENABLED.set(config.ingestEnabled);
            SECTION_RENDER_DISTANCE.set((double)config.sectionRenderDistance);
            SERVICE_THREADS.set(config.serviceThreads);
            SUB_DIVISION_SIZE.set((double)config.subDivisionSize);
            USE_ENVIRONMENTAL_FOG.set(config.getFogMode().hasFog);
            FOG_MODE.set(config.getFogMode());
            SSAO_MODE.set(config.getSSAOMode());
            DONT_USE_SODIUM_BUILDER_THREADS.set(config.dontUseSodiumBuilderThreads);
            LOD_BOUNDARY_BUFFER.set(config.lodBoundaryBuffer);
            EARTH_CURVE_RATIO.set(config.earthCurveRatio);
            SPEC.save();
        } finally { syncing = false; }
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncToVoxyConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            if (syncing) return;
            syncToVoxyConfig();
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                var holder = me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullableHolder();
                if (holder != null) holder.voxy$shutdownRenderer();
                me.cortex.voxy.commonImpl.VoxyCommon.shutdownInstance();
                if (me.cortex.voxy.client.ClientSessionEvents.inSession && VoxyConfig.CONFIG.enabled)
                    me.cortex.voxy.commonImpl.VoxyCommon.createInstance();
                if (holder != null) holder.voxy$createRenderer();
            });
        }
    }

    // Getters for direct access (optional, can use VoxyConfig.CONFIG instead)
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean isRenderingEnabled() {
        return ENABLE_RENDERING.get();
    }

    public static boolean isIngestEnabled() {
        return INGEST_ENABLED.get();
    }

    public static int getSectionRenderDistance() {
        return (int)Math.round(SECTION_RENDER_DISTANCE.get());
    }

    public static int getServiceThreads() {
        return SERVICE_THREADS.get();
    }

    public static float getSubDivisionSize() {
        return SUB_DIVISION_SIZE.get().floatValue();
    }

    public static boolean useEnvironmentalFog() {
        return USE_ENVIRONMENTAL_FOG.get();
    }

    public static boolean dontUseSodiumBuilderThreads() {
        return DONT_USE_SODIUM_BUILDER_THREADS.get();
    }

    public static int getLodBoundaryBuffer() {
        return LOD_BOUNDARY_BUFFER.get();
    }

    public static boolean isRenderStatisticsEnabled() {
        return RENDER_STATISTICS.get();
    }

    public static int getEarthCurveRatio() {
        return EARTH_CURVE_RATIO.get();
    }
}
