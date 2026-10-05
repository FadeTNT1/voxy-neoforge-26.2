package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** FML excludes this entire entrypoint on dedicated servers. */
@Mod(value = "voxy", dist = Dist.CLIENT)
public class VoxyClientMod {
    public VoxyClientMod(IEventBus bus, ModContainer container) {
        VoxyNeoForgeConfig.register(container);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(VoxyClient::onRegisterClientCommands);
        bus.addListener(DebugEntries::init);
    }

}
