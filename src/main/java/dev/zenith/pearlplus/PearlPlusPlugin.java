package dev.zenith.pearlplus;

import com.zenith.event.client.ClientBotTick;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.pearlplus.command.*;
import dev.zenith.pearlplus.module.*;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.EVENT_BUS;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Slightly better pearl loading module.",
    url = "https://github.com/duccss/pearlplus/",
    authors = {"duccss", "steve2b2t", "Leonetic"},
    mcVersions = "*" // mark every version compatible
)

public class PearlPlusPlugin implements ZenithProxyPlugin {
    public static PluginAPI API;
    public static PearlPlusConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        API = pluginAPI;
        LOG = pluginAPI.getLogger();
        LOG.info("PearlPlus Plugin loading...");
        PLUGIN_CONFIG = API.registerConfig(BuildConstants.PLUGIN_ID, PearlPlusConfig.class);
        API.registerCommand(new PearlPlusCommand());
        API.registerModule(new AutoLoadModule());
        API.registerModule(new AutoDetectModule());
        API.registerModule(new PearlRestockModule());
        EVENT_BUS.subscribe(
                this,
                of(ClientBotTick.class, event -> {
                    PearlManager.tickPendingLookClick();
                    PearlManager.tickPendingClaimReleases();
                }),
                of(ClientBotTick.Stopped.class, event -> {
                    PearlManager.cancelPendingLookClick();
                    PearlManager.cancelPendingClaimReleases();
                })
        );

        LOG.info("PearlPlus Plugin loaded!");
    }
}
