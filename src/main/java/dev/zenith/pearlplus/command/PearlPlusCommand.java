package dev.zenith.pearlplus.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.feature.api.minetools.MinetoolsApi;
import com.zenith.feature.api.minetools.model.MinetoolsUuidResponse;
import com.zenith.feature.api.minetools.model.MinetoolsProfileResponse;
import dev.zenith.pearlplus.module.AutoLoadModule;
import dev.zenith.pearlplus.module.AutoDetectModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                "allow <playerName> <pearlName>",
                "deny  <playerName> <pearlName>",
                "list",
                "strict <on/off>",
                "autodetect <on/off>",
                "autodetect temp <on/off>"
            )
            .aliases("pp")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("pearlplus")
            .requires(Command::validateAccountOwner)

            .then(argument("toggle", toggle()).executes(c -> {
                boolean enabled = getToggle(c, "toggle");
                PLUGIN_CONFIG.autoLoad.enabled = enabled;
                MODULE.get(AutoLoadModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                  .title("PearlPlus " + toggleStrCaps(enabled));
                return 0;
            }))

            .then(literal("allow")
              .then(argument("playerName", wordWithChars())
                .then(argument("pearlName", wordWithChars()).executes(c -> {
                    String name  = getString(c, "playerName");
                    String pearl = getString(c, "pearlName");

                    Optional<MinetoolsUuidResponse> result =
                        MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                    if (result.isEmpty()) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return 0;
                    }

                    UUID uuid = result.get().uuid();
                    List<String> list = PLUGIN_CONFIG.autoLoad.allowed.computeIfAbsent(uuid, k -> new ArrayList<>());
                    if (!list.contains(pearl)) list.add(pearl);

                    c.getSource().getEmbed()
                      .title("Allowed " + name + " → pearl " + pearl);
                    return 0;
              }))))

            .then(literal("deny")
              .then(argument("playerName", wordWithChars())
                .then(argument("pearlName", wordWithChars()).executes(c -> {
                    String name  = getString(c, "playerName");
                    String pearl = getString(c, "pearlName");

                    Optional<MinetoolsUuidResponse> result =
                        MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                    if (result.isEmpty()) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return 0;
                    }

                    UUID uuid = result.get().uuid();
                    List<String> list = PLUGIN_CONFIG.autoLoad.allowed.get(uuid);
                    if (list != null) {
                        list.remove(pearl);
                        if (list.isEmpty()) PLUGIN_CONFIG.autoLoad.allowed.remove(uuid);
                    }

                    c.getSource().getEmbed()
                      .title("Removed pearl " + pearl + " from " + name);
                    return 0;
              }))))

            .then(literal("list").executes(c -> {
                Embed e = c.getSource().getEmbed()
                  .title("Allowed Entries (" + PLUGIN_CONFIG.autoLoad.allowed.size() + ")");
                PLUGIN_CONFIG.autoLoad.allowed.forEach((uuid, pearls) -> {
                    String name = MinetoolsApi.INSTANCE.getProfileFromUUID(uuid)
                        .map(MinetoolsProfileResponse::name)
                        .orElse(uuid.toString());

                    e.addField(name, String.join(", ", pearls));
                });
                return 0;
            }))
            
            .then(literal("strict")
                  .then(argument("toggle", toggle()).executes(c -> {
                      boolean strict = getToggle(c, "toggle");
                      PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl = !strict;
                      c.getSource().getEmbed()
                          .title("PearlPlus strict " + toggleStrCaps(strict));
                      return 0;
            })))

            .then(literal("autodetect")
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
    }
}
