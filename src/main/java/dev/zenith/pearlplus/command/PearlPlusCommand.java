package dev.zenith.pearlplus.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.feature.api.minetools.MinetoolsApi;
import com.zenith.feature.api.minetools.model.MinetoolsUuidResponse;
import dev.zenith.pearlplus.PearlPlusConfig;
import dev.zenith.pearlplus.module.AutoLoadModule;
import dev.zenith.pearlplus.module.AutoDetectModule;
import dev.zenith.pearlplus.module.PearlManager;

import java.util.Optional;
import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlPlusCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pearlplus")
            .category(CommandCategory.MODULE)
            .description("Allow players to load pearls without whitelist through whispers.")
            .usageLines(
                "<on/off>",
                "default <playerName> <pearlName>",
                "list [playerName]",
                "set <playerName> <pearlId> <x> <y> <z>",
                "rename <playerName> <oldPearlId> <newPearlId>",
                "idword <word|none>",
                "strict <on/off>",
                "autodetect <on/off>",
                "autodetect temp <on/off>",
                "2b2t <on/off>"
            )
            .aliases("pp")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        LiteralArgumentBuilder<CommandContext> builder = command("pearlplus")
                .requires(Command::validateAccountOwner);

        builder.then(argument("toggle", toggle()).executes(c -> {
            boolean enabled = getToggle(c, "toggle");
            PLUGIN_CONFIG.autoLoad.enabled = enabled;
            MODULE.get(AutoLoadModule.class).syncEnabledFromConfig();
            c.getSource().getEmbed()
                    .title("PearlPlus " + toggleStrCaps(enabled));
            return 0;
        }));

        builder.then(literal("default")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlName", wordWithChars()).executes(c -> {
                            String name = getString(c, "playerName");
                            String pearl = getString(c, "pearlName");
                            Optional<MinetoolsUuidResponse> result =
                                    MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                            if (result.isEmpty()) {
                                c.getSource().getEmbed().title("Invalid username: " + name);
                                return 0;
                            }
                            UUID uuid = result.get().uuid();
                            PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                            manager.setDefaultPearl(uuid, pearl);
                            c.getSource().getEmbed().title("Default pearl for " + name + " set to " + pearl);
                            return 0;
                        }))));

        builder.then(literal("list")
                .executes(c -> {
                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                    String pearls = manager.pearlsListWithCoordsAllPlayers();
                    c.getSource().getEmbed().title("All Pearls").description(pearls);
                    return 0;
                })
                .then(argument("playerName", wordWithChars()).executes(c -> {
                    String name = getString(c, "playerName");
                    Optional<MinetoolsUuidResponse> result =
                            MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                    if (result.isEmpty()) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return 0;
                    }
                    UUID uuid = result.get().uuid();
                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                    String pearls = manager.pearlsListWithCoords(uuid);
                    c.getSource().getEmbed().title("Pearls for " + name).description(pearls);
                    return 0;
                })));

        builder.then(literal("set")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlId", wordWithChars())
                                .then(argument("x", integer())
                                        .then(argument("y", integer())
                                                .then(argument("z", integer()).executes(c -> {
                                                    String name = getString(c, "playerName");
                                                    String pearlId = getString(c, "pearlId");
                                                    Optional<MinetoolsUuidResponse> result =
                                                            MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                                                    if (result.isEmpty()) {
                                                        c.getSource().getEmbed().title("Invalid username: " + name);
                                                        return 0;
                                                    }

                                                    int x = getInteger(c, "x");
                                                    int y = getInteger(c, "y");
                                                    int z = getInteger(c, "z");

                                                    UUID uuid = result.get().uuid();
                                                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                                                    manager.recordPearl(uuid, name, pearlId, x, y, z);
                                                    c.getSource().getEmbed()
                                                            .title("Pearl stored for " + name)
                                                            .description(String.format("%s at %d %d %d", pearlId, x, y, z));
                                                    return 0;
                                                })))))));

        builder.then(literal("rename")
                .then(argument("playerName", wordWithChars())
                        .then(argument("oldPearlId", wordWithChars())
                                .then(argument("newPearlId", wordWithChars()).executes(c -> {
                                    String name = getString(c, "playerName");
                                    String oldPearlId = getString(c, "oldPearlId");
                                    String newPearlId = getString(c, "newPearlId");
                                    Optional<MinetoolsUuidResponse> result =
                                            MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                                    if (result.isEmpty()) {
                                        c.getSource().getEmbed().title("Invalid username: " + name);
                                        return 0;
                                    }

                                    UUID uuid = result.get().uuid();
                                    PearlPlusConfig.PlayerPearls playerPearls = PLUGIN_CONFIG.players.get(uuid);
                                    if (playerPearls == null || !playerPearls.pearls.containsKey(oldPearlId)) {
                                        c.getSource().getEmbed().title("Pearl not found for " + name);
                                        return 0;
                                    }
                                    if (playerPearls.pearls.containsKey(newPearlId)) {
                                        c.getSource().getEmbed().title("A pearl with that id already exists for " + name);
                                        return 0;
                                    }

                                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                                    boolean renamed = manager.renamePearl(uuid, oldPearlId, newPearlId);
                                    if (renamed) {
                                        c.getSource().getEmbed().title("Renamed " + oldPearlId + " to " + newPearlId + " for " + name);
                                    } else {
                                        c.getSource().getEmbed().title("Unable to rename pearl for " + name);
                                    }
                                    return 0;
                                })))));

        builder.then(literal("idword")
                .then(argument("word", wordWithChars()).executes(c -> {
                    String word = getString(c, "word");
                    if ("none".equalsIgnoreCase(word)) {
                        PLUGIN_CONFIG.defaultPearlIdBase = null;
                        c.getSource().getEmbed().title("Pearl ID word cleared; using player names");
                    } else {
                        PLUGIN_CONFIG.defaultPearlIdBase = word;
                        c.getSource().getEmbed().title("Pearl ID word set to '" + word + "'");
                    }
                    return 0;
                })));

        builder.then(literal("strict")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean strict = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl = !strict;
                    c.getSource().getEmbed()
                            .title("PearlPlus strict " + toggleStrCaps(strict));
                    return 0;
                })));

        builder.then(literal("autodetect")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoDetect.enabled = enabled;

                    AutoDetectModule module = MODULE.get(AutoDetectModule.class);
                    module.syncEnabledFromConfig();
                    if (enabled) {
                        module.markExistingPearls();
                    }

                    c.getSource().getEmbed()
                            .title("PearlPlus Autodetect " + toggleStrCaps(enabled));
                    return 0;
                }))
                .then(literal("temp")
                        .then(argument("toggle", toggle()).executes(c -> {
                            boolean enabled = getToggle(c, "toggle");
                            PLUGIN_CONFIG.autoDetect.temporaryMode = enabled;

                            AutoDetectModule module = MODULE.get(AutoDetectModule.class);
                            module.onTemporaryModeToggle(enabled);

                            c.getSource().getEmbed()
                                    .title("PearlPlus Autodetect Temp Mode " + toggleStrCaps(enabled));
                            return 0;
                        }))));

        builder.then(literal("2b2t")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoDetect.twoBtwoTMode = enabled;

                    c.getSource().getEmbed()
                            .title("PearlPlus 2b2t Mode " + toggleStrCaps(enabled));
                    return 0;
                })));

        return builder;
    }
}
