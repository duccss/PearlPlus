package dev.zenith.pearlplus;

import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.zenith.pearlplus.command.PearlPlusCommand;
import dev.zenith.pearlplus.module.PearlPlusModule;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;

@Plugin(
    id = "pearl-plus",
    version = "1.1.1",
    description = "Load pearls through whispers without whitelist.",
    url = "https://github.com/duccss/",
    authors = {"duccss"},
    mcVersions = {"1.21.0", "1.21.4", "1.21.5", "1.21.7", "1.21.8"}
)

public class PearlPlusPlugin implements ZenithProxyPlugin {
    public static PluginAPI API;
    public static PearlPlusConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        API = pluginAPI;
        LOG = pluginAPI.getLogger();
        LOG.info("Pearl+ Plugin loading...");
        PLUGIN_CONFIG = API.registerConfig("pearl-plus", PearlPlusConfig.class);
        API.registerCommand(new PearlPlusCommand());
        API.registerModule(new PearlPlusModule());
        LOG.info("Pearl+ Plugin loaded!");
    }
}
