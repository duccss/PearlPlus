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
    version = "1.0.0",
    description = "Pearl+ loads pearls through whispers without whitelist.",
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
        PLUGIN_CONFIG = API.registerConfig("pearl-plus", PearlPlusConfig.class);
        API.registerCommand(new PearlPlusCommand());
        API.registerModule(new PearlPlusModule());
    }

    public static void saveConfig() {
        try {
            Path path = Paths.get("config", "pearl-plus.json");
            Files.createDirectories(path.getParent());
            new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(path.toFile(), PLUGIN_CONFIG);
        } catch (IOException e) {
            LOG.error("Failed to save Pearl+ config", e);
        }
    }
}
