package dev.zenith.pearlplus.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.pearlplus.PearlPlusPlugin;
import dev.zenith.pearlplus.module.PearlPlusModule;

import java.util.ArrayList;
import java.util.List;

import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;
import static dev.zenith.pearlplus.PearlPlusPlugin.saveConfig;

public class PearlPlusCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pearl+")
            .category(CommandCategory.MODULE)
            .description("Allow players to load pearls without whitelist through whispers.")
            .usageLines(
                "toggle <on/off>",
                "allow <playerName> <pearlName>",
                "deny  <playerName> <pearlName>",
                "list"
            )
            .aliases("pp")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("pearl+")
            .requires(Command::validateAccountOwner)

            .then(argument("toggle", toggle()).executes(c -> {
                boolean enabled = getToggle(c, "toggle");
                PLUGIN_CONFIG.enabled = enabled;
                MODULE.get(PearlPlusModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                  .title("Pearl+ " + toggleStrCaps(enabled));
                return 0;
            }))

            .then(literal("allow")
              .then(argument("playerName", wordWithChars())
                .then(argument("pearlName", wordWithChars()).executes(c -> {
                    String name  = getString(c, "playerName");
                    String pearl = getString(c, "pearlName");

                    List<String> list = PLUGIN_CONFIG.allowed
                      .computeIfAbsent(name, k -> new ArrayList<>());
                    if (!list.contains(pearl)) list.add(pearl);

                    c.getSource().getEmbed()
                      .title("Allowed " + name + " → " + pearl);
                    return 0;
              }))))

            .then(literal("deny")
              .then(argument("playerName", wordWithChars())
                .then(argument("pearlName", wordWithChars()).executes(c -> {
                    String name  = getString(c, "playerName");
                    String pearl = getString(c, "pearlName");

                    List<String> list = PLUGIN_CONFIG.allowed.get(name);
                    if (list != null) {
                        list.remove(pearl);
                        if (list.isEmpty()) PLUGIN_CONFIG.allowed.remove(name);
                    }

                    c.getSource().getEmbed()
                      .title("Removed " + pearl + " from " + name);
                    return 0;
              }))))

            .then(literal("list").executes(c -> {
                Embed e = c.getSource().getEmbed()
                  .title("Allowed Entries (" + PLUGIN_CONFIG.allowed.size() + ")");
                PLUGIN_CONFIG.allowed.forEach((player, pearls) ->
                  e.addField(player, String.join(", ", pearls))
                );
                return 0;
            }));
    }
}
