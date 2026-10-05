package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.common.util.AtomicFiles;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public String fogMode;
    // Preserve settings from the previous native port and legacy JSON configs.
    public Boolean useEnvironmentalFog;
    public int lodBoundaryBuffer = 1;
    public int earthCurveRatio = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public String ssaoMode;

    public SSAO.SSAOMode getSSAOMode() {
        var DEFAULT = SSAO.SSAOMode.AUTO;
        if (this.ssaoMode == null) return DEFAULT;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return DEFAULT; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }


    public NormalRenderPipeline.FogMode getFogMode() {
        var DEFAULT = NormalRenderPipeline.FogMode.FOG_AND_FADE;
        if (this.fogMode == null) return Boolean.FALSE.equals(this.useEnvironmentalFog) ? NormalRenderPipeline.FogMode.OFF : DEFAULT;
        try {
            return NormalRenderPipeline.FogMode.valueOf(this.fogMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return DEFAULT;}
    }

    public void setFogMode(NormalRenderPipeline.FogMode mode) {
        this.fogMode = mode.name().toLowerCase(Locale.ROOT);
    }


    private static VoxyConfig loadOrCreate() {
        // FML loads configs before renderer initialization; availability is not a
        // reason to discard persisted settings (especially on Vulkan).
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                var config = GSON.fromJson(reader, VoxyConfig.class);
                if (config != null) {
                    if (config.earthCurveRatio > 0 && config.earthCurveRatio < 50) config.earthCurveRatio = 50;
                    return config;
                }
            } catch (IOException | JsonParseException e) {
                Logger.error("Could not load Voxy config", e);
            }
        }
        return new VoxyConfig();
    }

    public void save() {
        VoxyNeoForgeConfig.saveFrom(this);
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.createDirectories(getConfigPath().getParent());
            AtomicFiles.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
