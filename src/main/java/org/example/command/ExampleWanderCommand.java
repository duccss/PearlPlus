package org.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import org.example.module.ExampleWanderModule;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static org.example.ExamplePlugin.PLUGIN_CONFIG;

public class ExampleWanderCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("wander")
            .category(CommandCategory.MODULE)
            .description("""
                Randomly moves the player around in the world
                """)
            .usageLines(
                "on/off",
                "radius <blocks>",
                "minRadius <blocks"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("wander")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.wander.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Wander " + toggleStrCaps(PLUGIN_CONFIG.wander.enabled));
                MODULE.get(ExampleWanderModule.class).syncEnabledFromConfig();
            }))
            .then(literal("radius").then(argument("blocks", integer(1)).executes(c -> {
                int radius = getInteger(c, "blocks");
                if (radius < PLUGIN_CONFIG.wander.minRadius) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Radius must be greater than minRadius");
                    return ERROR;
                }
                PLUGIN_CONFIG.wander.radius = radius;
                c.getSource().getEmbed()
                    .title("Radius Set");
                return OK;
            })))
            .then(literal("minRadius").then(argument("blocks", integer(1)).executes(c -> {
                int radius = getInteger(c, "blocks");
                if (radius > PLUGIN_CONFIG.wander.radius) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Min Radius must be less than radius");
                    return ERROR;
                }
                PLUGIN_CONFIG.wander.minRadius = radius;
                c.getSource().getEmbed()
                    .title("Min Radius Set");
                return OK;
            })));

    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .addField("Wander", toggleStr(PLUGIN_CONFIG.wander.enabled))
            .addField("Radius", PLUGIN_CONFIG.wander.radius)
            .addField("Min Radius", PLUGIN_CONFIG.wander.minRadius)
            .primaryColor();
    }
}
