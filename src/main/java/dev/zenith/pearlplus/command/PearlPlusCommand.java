package dev.zenith.pearlplus.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.feature.whitelist.PlayerListsManager;
import dev.zenith.pearlplus.module.AutoLoadModule;
import dev.zenith.pearlplus.module.AutoDetectModule;
import dev.zenith.pearlplus.module.PearlManager;

import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;
import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;

public class PearlPlusCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pearlplus")
            .category(CommandCategory.MODULE)
            .description("Allow players to load pearls without whitelist through whispers.")
            .usageLines(
                "<on/off>",
                "list",
                "list clear",
                "add <playerName> <pearlId> <x> <y> <z>",
                "del <playerName> <pearlId>",
                "defaultpearlid <word|none>",
                "load <playerName> <pearlId>",
                "returnpos <on/off>",
                "strict <on/off>",
                "loadcommand <word>",
                "autodetect <on/off>",
                "autodetect temp <on/off>",
                "distancecheck <on/off>",
                "autodefault <on/off>",
                "whitelist <on/off / add / clear / list / remove>",
                "droppearlafterload <on/off>"
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

        builder.then(literal("list")
                .executes(c -> {
                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                    String pearls = manager.pearlsListWithCoordsAllPlayers();
                    c.getSource().getEmbed().title("All Pearls").description(pearls);
                    return 0;
                })
                .then(literal("clear").executes(c -> {
                    int playerCount = PLUGIN_CONFIG.players.size();
                    int pearlCount = PLUGIN_CONFIG.players.values().stream()
                            .mapToInt(playerPearls -> playerPearls.pearls.size())
                            .sum();
                    PLUGIN_CONFIG.players.clear();
                    c.getSource().getEmbed()
                            .title("Cleared pearls (" + pearlCount + " pearls removed from " + playerCount + " players)");
                    LOG.info("Cleared pearls ({} pearls removed from {} players)", pearlCount, playerCount);
                    return 0;
                }))
                .then(argument("playerName", wordWithChars()).executes(c -> {
                    String name = getString(c, "playerName");
                    UUID uuid = resolveUuidByUsername(name);
                    if (uuid == null) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return 0;
                    }

                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                    String pearls = manager.pearlsListWithCoords(uuid);
                    c.getSource().getEmbed().title("Pearls for " + name).description(pearls);
                    return 0;
                })));

        builder.then(literal("add")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlId", wordWithChars())
                                .then(argument("x", integer())
                                        .then(argument("y", integer())
                                                .then(argument("z", integer()).executes(c -> {
                                                    String name = getString(c, "playerName");
                                                    String pearlId = getString(c, "pearlId");
                                                    UUID uuid = resolveUuidByUsername(name);
                                                    if (uuid == null) {
                                                        c.getSource().getEmbed().title("Invalid username: " + name);
                                                        return 0;
                                                    }

                                                    int x = getInteger(c, "x");
                                                    int y = getInteger(c, "y");
                                                    int z = getInteger(c, "z");

                                                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                                                    manager.recordPearl(uuid, name, pearlId, x, y, z);
                                                    c.getSource().getEmbed()
                                                            .title("Pearl stored for " + name)
                                                            .description(String.format("%s at ||%d %d %d||", pearlId, x, y, z));
                                                    return 0;
                                                })))))));

        builder.then(literal("del")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlId", wordWithChars()).executes(c -> {
                            String name = getString(c, "playerName");
                            String pearlId = getString(c, "pearlId");
                            UUID uuid = resolveUuidByUsername(name);
                            if (uuid == null) {
                                c.getSource().getEmbed().title("Invalid username: " + name);
                                return 0;
                            }

                            PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                            String resolvedPearlId = manager.resolvePearlId(uuid, pearlId);
                            if (resolvedPearlId == null) {
                                c.getSource().getEmbed().title("Pearl not found for " + name);
                                return 0;
                            }

                            manager.removePearl(uuid, resolvedPearlId);
                            c.getSource().getEmbed().title("Removed pearl " + resolvedPearlId + " for " + name);
                            return 0;
                        }))));

        builder.then(literal("load")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlId", wordWithChars()).executes(c -> {
                            String name = getString(c, "playerName");
                            String pearlId = getString(c, "pearlId");
                            UUID uuid = resolveUuidByUsername(name);
                            if (uuid == null) {
                                c.getSource().getEmbed().title("Invalid username: " + name);
                                return 0;
                            }

                            PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                            String resolvedPearlId = manager.resolvePearlId(uuid, pearlId);
                            if (resolvedPearlId == null) {
                                c.getSource().getEmbed().title("Pearl not found for " + name);
                                return 0;
                            }

                            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
                            if (playerEntry == null || !playerEntry.pearls.containsKey(resolvedPearlId)) {
                                c.getSource().getEmbed().title("No authorized pearls found for " + name);
                                return 0;
                            }

                            manager.loadPearl(playerEntry.pearls.get(resolvedPearlId), null);
                            c.getSource().getEmbed().title("Loading pearl " + resolvedPearlId + " for " + name);
                            return 0;
                        }))));
        
        builder.then(literal("defaultpearlid")
                .then(argument("word", wordWithChars()).executes(c -> {
                    String word = getString(c, "word");
                    if ("none".equalsIgnoreCase(word)) {
                        PLUGIN_CONFIG.defaultPearlId = null;
                        c.getSource().getEmbed().title("Pearl ID word cleared; using player names");
                    } else {
                        PLUGIN_CONFIG.defaultPearlId = word;
                        c.getSource().getEmbed().title("Pearl ID word set to '" + word + "'");
                    }
                    return 0;
                })));


        builder.then(literal("loadcommand")
                .then(argument("word", wordWithChars()).executes(c -> {
                    String word = getString(c, "word").trim();
                    if (word.isEmpty()) {
                        c.getSource().getEmbed().title("Load trigger word cannot be empty");
                        return 0;
                    }
                    PLUGIN_CONFIG.autoLoad.loadCommand = word;
                    c.getSource().getEmbed().title("Load trigger word set to '" + word + "'");
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

        builder.then(literal("returnpos")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.returnToStartPos = enabled;
                    c.getSource().getEmbed()
                            .title("PearlPlus Return to Start " + toggleStrCaps(enabled));
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

        builder.then(literal("distancecheck")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoDetect.distanceCheck = enabled;

                    c.getSource().getEmbed()
                            .title("PearlPlus Distance Check " + toggleStrCaps(enabled));
                    return 0;
                })));

        builder.then(literal("autodefault")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.autoDefaultToPresent = enabled;
                    c.getSource().getEmbed()
                            .title("PearlPlus Auto Default " + toggleStrCaps(enabled));
                    return 0;
                })));

        builder.then(literal("whitelist")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.whitelistEnabled = enabled;
                    c.getSource().getEmbed()
                            .title("Whitelist " + toggleStrCaps(enabled));
                    return 0;
                }))
                .then(literal("add")
                        .then(argument("playerName", wordWithChars()).executes(c -> {
                            String playerName = getString(c, "playerName");
                            UUID uuid = resolveUuidByUsername(playerName);
                            if (uuid == null) {
                                c.getSource().getEmbed().title("Invalid username: " + playerName);
                                return 0;
                            }

                            if (PLUGIN_CONFIG.whitelist.containsKey(uuid)) {
                                c.getSource().getEmbed().title(playerName + " is already whitelisted");
                                return 0;
                            }
                            PLUGIN_CONFIG.whitelist.put(uuid, new dev.zenith.pearlplus.PearlPlusConfig.WhitelistedPlayer(playerName, uuid));
                            c.getSource().getEmbed().title("Added " + playerName + " to whitelist");
                            LOG.info("Added " + playerName + " (" + uuid + ") to whitelist");
                            return 0;
                        })))
                .then(literal("remove")
                        .then(argument("playerName", wordWithChars()).executes(c -> {
                            String playerName = getString(c, "playerName");
                            UUID uuid = resolveUuidByUsername(playerName);
                            if (uuid == null) {
                                c.getSource().getEmbed().title("Invalid username: " + playerName);
                                return 0;
                            }

                            if (!PLUGIN_CONFIG.whitelist.containsKey(uuid)) {
                                c.getSource().getEmbed().title(playerName + " is not in the whitelist");
                                return 0;
                            }
                            PLUGIN_CONFIG.whitelist.remove(uuid);
                            c.getSource().getEmbed().title("Removed " + playerName + " from whitelist");
                            LOG.info("Removed " + playerName + " (" + uuid + ") from whitelist");
                            return 0;
                        })))
                .then(literal("list").executes(c -> {
                    if (PLUGIN_CONFIG.whitelist.isEmpty()) {
                        c.getSource().getEmbed().title("Whitelist is empty");
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (dev.zenith.pearlplus.PearlPlusConfig.WhitelistedPlayer player : PLUGIN_CONFIG.whitelist.values()) {
                        sb.append("- ").append(player.username).append(" (").append(player.uuid).append(")\n");
                    }
                    c.getSource().getEmbed()
                            .title("Whitelist (" + PLUGIN_CONFIG.whitelist.size() + " players)")
                            .description(sb.toString().trim());
                    return 0;
                }))
                .then(literal("clear").executes(c -> {
                    int count = PLUGIN_CONFIG.whitelist.size();
                    PLUGIN_CONFIG.whitelist.clear();
                    c.getSource().getEmbed().title("Cleared whitelist (" + count + " players removed)");
                    LOG.info("Cleared whitelist (" + count + " players removed)");
                    return 0;
                })));
                
                builder.then(literal("droppearlafterload")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean dropPearlAfterLoad = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad = dropPearlAfterLoad;
                    c.getSource().getEmbed()
                            .title("Drop pearl after load " + toggleStrCaps(dropPearlAfterLoad));
                    return 0;
                })));

        return builder;
    }

    @Override
    public void defaultEmbed(final Embed builder) {
        String defaultPearlId = PLUGIN_CONFIG.defaultPearlId == null ? "None" : PLUGIN_CONFIG.defaultPearlId;
        builder
                .addField("Enabled", toggleStr(PLUGIN_CONFIG.autoLoad.enabled))
                .addField("Default Pearl ID", defaultPearlId)
                .addField("Return Position", toggleStr(PLUGIN_CONFIG.autoLoad.returnToStartPos))
                .addField("Strict", toggleStr(!PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl))
                .addField("Load Command", PLUGIN_CONFIG.autoLoad.loadCommand)
                .addField("Autodetect", toggleStr(PLUGIN_CONFIG.autoDetect.enabled))
                .addField("Autodetect Temp", toggleStr(PLUGIN_CONFIG.autoDetect.temporaryMode))
                .addField("Distance Check", toggleStr(PLUGIN_CONFIG.autoDetect.distanceCheck))
                .addField("Auto Default", toggleStr(PLUGIN_CONFIG.autoLoad.autoDefaultToPresent))
                .addField("Whitelist", toggleStr(PLUGIN_CONFIG.autoLoad.whitelistEnabled))
                .addField("Drop Pearl After Load", toggleStr(PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad))
                .primaryColor();
    }

    private UUID resolveUuidByUsername(final String username) {
        return PlayerListsManager.getProfileFromUsername(username)
                .map(profile -> profile.uuid())
                .orElse(null);
    }
}
