package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;

import java.util.List;
import java.util.UUID;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoLoadModule extends Module {
    private final PearlManager pearlManager = new PearlManager(this);

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoLoad.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(WhisperChatEvent.class, this::onWhisper)
        );
    }

    private void onWhisper(WhisperChatEvent event) {
        if (!PLUGIN_CONFIG.autoLoad.enabled || event.outgoing()) return;

        String rawMessage = event.message().trim();
        String msg = rawMessage.toLowerCase();
        var sender = event.sender();
        String name = sender.getName();
        UUID uuid = sender.getProfileId();

        if (msg.equals("pearls")) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry != null && !playerEntry.pearls.isEmpty()) {
                String list = pearlManager.pearlsList(uuid);
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, list));
            }
            return;
        }

        if (!msg.startsWith("load")) return;

        var playerEntry = PLUGIN_CONFIG.players.get(uuid);
        if (playerEntry == null || playerEntry.pearls.isEmpty()) {
            info("No pearls assigned to " + name);
            return;
        }

        String[] parts = msg.split("\\s+");
        String requestedPearl;

        if (!PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl) {
            if (parts.length > 2) {
                info("Extra arguments not allowed for " + name);
                return;
            }
        } else if (parts.length > 3) {
            info("Too many arguments from " + name);
            return;
        }

        if (parts.length == 1) {
            requestedPearl = pearlManager.defaultPearlId(uuid);
        } else {
            String candidate = parts[1];
            String resolved = pearlManager.resolvePearlId(uuid, candidate);
            if (resolved != null) {
                requestedPearl = resolved;
            } else if (PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl) {
                requestedPearl = pearlManager.defaultPearlId(uuid);
            } else {
                requestedPearl = null;
            }
        }

        if (requestedPearl == null || !playerEntry.pearls.containsKey(requestedPearl)) {
            info("Unauthorized load from " + name + " with arg: " + rawMessage);
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "No authorized pearls found."));
            return;
        }

        var pearl = playerEntry.pearls.get(requestedPearl);

        discordAndIngameNotification(com.zenith.discord.Embed.builder()
                .title("Recieved Whisper")
                .addField("Sender", name)
                .addField("PearlID", requestedPearl)
        );

        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Loading pearl: " + requestedPearl + "..."));

        if (!pearlManager.isPearlPresent(pearl)) {
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "No pearl detected. Attempting to load anyways."));
        }

        pearlManager.loadPearl(pearl, name);
    }
}
