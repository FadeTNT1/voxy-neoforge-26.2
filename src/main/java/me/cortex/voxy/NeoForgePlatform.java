package me.cortex.voxy;

import net.neoforged.fml.loading.FMLLoader;

/** Availability checks used during early mixin bootstrap and normal runtime. */
public final class NeoForgePlatform {
    private NeoForgePlatform() {}

    public static boolean isModLoaded(String id) {
        var loader = FMLLoader.getCurrentOrNull();
        return loader != null && loader.getLoadingModList() != null
                && loader.getLoadingModList().getModFileById(id) != null;
    }
}
