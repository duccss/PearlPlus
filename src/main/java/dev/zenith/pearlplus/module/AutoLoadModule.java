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
        String[] lowerParts = msg.split("\\s+");
        String[] parts = rawMessage.trim().split("\\s+");
        var sender = event.sender();
        String name = sender.getName();
        UUID uuid = sender.getProfileId();

        // Check whitelist for load commands
        if (msg.startsWith("load")) {
            if (!PLUGIN_CONFIG.whitelist.isEmpty() && !PLUGIN_CONFIG.whitelist.contains(uuid)) {
                // Non-whitelisted player trying to load - ignore silently
                return;
            }
        }

        if (msg.equals("pearls")) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry != null && !playerEntry.pearls.isEmpty()) {
                String list = pearlManager.pearlsList(uuid);
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, list));
            }
            return;
        }

        if (lowerParts.length > 0 && "default".equals(lowerParts[0])) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry == null || playerEntry.pearls.isEmpty()) {
                info("Default request from player without pearls: " + name);
                return;
            }
            if (parts.length < 2) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Specify a pearl ID to set as default."));
                return;
            }
            String resolved = pearlManager.resolvePearlId(uuid, parts[1]);
            if (resolved == null) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Pearl not found."));
                return;
            }
            pearlManager.setDefaultPearl(uuid, resolved);
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Default pearl set to " + resolved + "."));
            return;
        }

        if (lowerParts.length > 0 && "rename".equals(lowerParts[0])) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry == null || playerEntry.pearls.isEmpty()) {
                info("Rename request from player without pearls: " + name);
                return;
            }
            if (parts.length < 3) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Usage: rename <oldId> <newId>"));
                return;
            }
            String oldPearlId = pearlManager.resolvePearlId(uuid, parts[1]);
            if (oldPearlId == null) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Pearl not found."));
                return;
            }
            String newPearlId = parts[2];
            boolean exists = playerEntry.pearls.keySet().stream()
                    .anyMatch(id -> id.equalsIgnoreCase(newPearlId));
            if (exists) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "A pearl with that id already exists."));
                return;
            }
            boolean renamed = pearlManager.renamePearl(uuid, oldPearlId, newPearlId);
            if (renamed) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Renamed " + oldPearlId + " to " + newPearlId + "."));
            } else {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Unable to rename pearl."));
            }
            return;
        }

        if (!msg.startsWith("load")) return;

        var playerEntry = PLUGIN_CONFIG.players.get(uuid);
        if (playerEntry == null || playerEntry.pearls.isEmpty()) {
            info("No pearls assigned to " + name);
            return;
        }

        String requestedPearl;

        if (!PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl) {
            if (lowerParts.length > 2) {
                info("Extra arguments not allowed for " + name);
                return;
            }
        } else if (lowerParts.length > 3) {
            info("Too many arguments from " + name);
            return;
        }

        if (lowerParts.length == 1) {
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
                .addField("Pearl", requestedPearl)
        );

        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Loading pearl " + requestedPearl + "..."));

        if (!pearlManager.isPearlPresent(pearl)) {
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "No pearl detected. Attempting to load anyways."));
        }

        pearlManager.loadPearl(pearl, name);
    }
}
