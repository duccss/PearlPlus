package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.util.ChatUtil;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import dev.zenith.pearlplus.PearlPlusConfig;
import com.zenith.module.api.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlManager {
    private final Module notifier;

    public PearlManager(Module notifier) {
        this.notifier = notifier;
    }

    public record PlayerPearl(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {
    }

    public Optional<PlayerPearl> findPearl(UUID ownerUuid, String pearlId) {
        if (ownerUuid == null || pearlId == null || pearlId.isBlank()) {
            return Optional.empty();
        }
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return Optional.empty();
        PearlPlusConfig.StoredPearl stored = entry.pearls.get(pearlId);
        if (stored == null) return Optional.empty();
        return Optional.of(new PlayerPearl(ownerUuid, entry.playerName, stored));
    }

    public List<PearlPlusConfig.StoredPearl> listPearls(UUID ownerUuid) {
        if (ownerUuid == null) return List.of();
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return List.of();
        return new ArrayList<>(entry.pearls.values());
    }

    public PearlPlusConfig.StoredPearl recordPearl(UUID ownerUuid, String ownerName, String pearlId, int x, int y, int z) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.computeIfAbsent(ownerUuid, uuid -> new PearlPlusConfig.PlayerPearls());
        entry.playerName = ownerName;
        if (entry.defaultPearlId == null || entry.defaultPearlId.isBlank()) {
            entry.defaultPearlId = pearlId;
        }
        PearlPlusConfig.StoredPearl stored = entry.pearls.computeIfAbsent(pearlId, id -> new PearlPlusConfig.StoredPearl());
        stored.pearlId = pearlId;
        stored.x = x;
        stored.y = y;
        stored.z = z;
        return stored;
    }

    public void removePearl(UUID ownerUuid, String pearlId) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return;
        entry.pearls.remove(pearlId);
        if (pearlId != null && pearlId.equals(entry.defaultPearlId)) {
            entry.defaultPearlId = entry.pearls.keySet().stream().findFirst().orElse(null);
        }
        if (entry.pearls.isEmpty()) {
            PLUGIN_CONFIG.players.remove(ownerUuid);
        }
    }

    public boolean renamePearl(UUID ownerUuid, String oldPearlId, String newPearlId) {
        if (ownerUuid == null || oldPearlId == null || newPearlId == null
                || oldPearlId.isBlank() || newPearlId.isBlank()) {
            return false;
        }

        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return false;

        PearlPlusConfig.StoredPearl existing = entry.pearls.get(oldPearlId);
        if (existing == null) return false;
        if (entry.pearls.containsKey(newPearlId)) return false;

        entry.pearls.remove(oldPearlId);
        existing.pearlId = newPearlId;
        entry.pearls.put(newPearlId, existing);

        if (oldPearlId.equals(entry.defaultPearlId)) {
            entry.defaultPearlId = newPearlId;
        }
        return true;
    }

    public String defaultPearlId(UUID ownerUuid) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return null;
        String configuredDefault = entry.defaultPearlId;
        if (configuredDefault != null && entry.pearls.containsKey(configuredDefault)) {
            if (!PLUGIN_CONFIG.autoLoad.autoDefaultToPresent || isPearlPresent(entry.pearls.get(configuredDefault))) {
                return configuredDefault;
            }
        }

        if (PLUGIN_CONFIG.autoLoad.autoDefaultToPresent) {
            for (Map.Entry<String, PearlPlusConfig.StoredPearl> pearlEntry : entry.pearls.entrySet()) {
                if (isPearlPresent(pearlEntry.getValue())) {
                    return pearlEntry.getKey();
                }
            }
        }

        if (configuredDefault != null && entry.pearls.containsKey(configuredDefault)) {
            return configuredDefault;
        }

        return entry.pearls.keySet().stream().findFirst().orElse(null);
    }

    public void setDefaultPearl(UUID ownerUuid, String pearlId) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return;
        if (pearlId != null && entry.pearls.containsKey(pearlId)) {
            entry.defaultPearlId = pearlId;
        }
    }

    public String resolvePearlId(UUID ownerUuid, String pearlId) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || pearlId == null) return null;
        return entry.pearls.keySet().stream()
                .filter(id -> id.equalsIgnoreCase(pearlId))
                .findFirst()
                .orElse(null);
    }

    public boolean isPearlPresent(PearlPlusConfig.StoredPearl pearl) {
        if (pearl == null || CACHE == null || CACHE.getEntityCache() == null) {
            return false;
        }
        if (!isWithinPresenceRange(pearl)) {
            return false;
        }
        return CACHE.getEntityCache().getEntities().values().stream()
                .anyMatch(entity -> entity.getEntityType() == org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.ENDER_PEARL
                        && Math.floor(entity.getX()) == pearl.x
                        && Math.floor(entity.getZ()) == pearl.z);
    }

    private boolean isWithinPresenceRange(PearlPlusConfig.StoredPearl pearl) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        var player = CACHE.getPlayerCache().getThePlayer();
        double dx = player.getX() - pearl.x;
        double dy = player.getY() - pearl.y;
        double dz = player.getZ() - pearl.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= PLUGIN_CONFIG.autoDetect.temporaryRemovalRange;
    }

    public void loadPearl(PearlPlusConfig.StoredPearl pearl, String requesterName) {
        if (pearl == null) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            notifier.discordAndIngameNotification(Embed.builder().title("Can't Load Pearl").description("Bot is not online").errorColor());
            return;
        }
        if (proxy.hasActivePlayer()) {
            notifier.discordAndIngameNotification(Embed.builder().title("Can't Load Pearl").description("Player is controlling").errorColor());
            return;
        }
        
        // Make sure there is a pearl to drop for the player before walking.
        if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad == true) {
            ensurePearlsAvailable();
        }

        BlockPos current = CACHE.getPlayerCache().getThePlayer().blockPos();
        BARITONE.rightClickBlock(pearl.x, pearl.y, pearl.z)
                .addExecutedListener(f -> {
                    var builder = Embed.builder()
                            .title("Pearl Loaded!")
                            .addField("Pearl ID", pearl.pearlId, false)
                            .successColor();
                    if (requesterName != null) {
                        builder.addField("Requested By", requesterName, false);
                    }
                    notifier.discordAndIngameNotification(builder);
                    
                     // Drop a pearl when loaded.
                    if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad == true) {
                        handlePearlDropAfterLoad(requesterName);
                    }

                    if (PLUGIN_CONFIG.autoLoad.returnToStartPos) {
                        BARITONE.pathTo(current.x(), current.z())
                                .addExecutedListener(f2 -> notifier.discordAndIngameNotification(Embed.builder()
                                        .description("Returned to start pos")
                                        .successColor()));
                    }
                });
        notifier.discordAndIngameNotification(Embed.builder().title("Loading Pearl").addField("Pearl", pearl.pearlId, false).primaryColor());
    }

    public String pearlsList(UUID ownerUuid) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return "None";

        StringBuilder sb = new StringBuilder("PearlIDs: ");
        boolean first = true;
        for (Map.Entry<String, PearlPlusConfig.StoredPearl> e : entry.pearls.entrySet()) {
            PearlPlusConfig.StoredPearl pearl = e.getValue();
            if (!first) {
                sb.append(", ");
            }
            sb.append(pearl.pearlId);
            if (!isPearlPresent(pearl)) {
                sb.append("*");
            }
            first = false;
        }
        return sb.toString();
    }

    public String pearlsListWithCoords(UUID ownerUuid) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, PearlPlusConfig.StoredPearl> e : entry.pearls.entrySet()) {
            PearlPlusConfig.StoredPearl pearl = e.getValue();
            sb.append("**").append(pearl.pearlId).append("**");
            sb.append(": ");
            if (CONFIG.discord.reportCoords) {
                sb.append("||[").append(pearl.x).append(", ").append(pearl.y).append(", ").append(pearl.z).append("]||");
            } else {
                sb.append("coords hidden");
            }
            sb.append("\n");
        }
        String result = sb.toString();
        return result.isBlank() ? "None" : result.substring(0, result.length() - 1);
    }

    public String pearlsListWithCoordsAllPlayers() {
        if (PLUGIN_CONFIG.players.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            PearlPlusConfig.PlayerPearls playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null || playerPearls.pearls.isEmpty()) {
                continue;
            }
            String playerName = playerPearls.playerName != null ? playerPearls.playerName : entry.getKey().toString();
            sb.append("**").append(playerName).append("**").append("\n");
            for (PearlPlusConfig.StoredPearl pearl : playerPearls.pearls.values()) {
                sb.append("- ").append(pearl.pearlId);
                sb.append(": ");
                if (CONFIG.discord.reportCoords) {
                    sb.append("||[").append(pearl.x).append(", ").append(pearl.y).append(", ").append(pearl.z).append("]||");
                } else {
                    sb.append("coords hidden");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        String result = sb.toString().trim();
        return result.isBlank() ? "None" : result;
    }

    public String nextAvailablePearlId(UUID ownerUuid, String ownerName) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        String configuredBase = PLUGIN_CONFIG.defaultPearlId;
        String base;
        if (configuredBase != null && !configuredBase.isBlank()) {
            base = configuredBase;
        } else {
            base = (ownerName == null || ownerName.isBlank()) ? "pearl" : ownerName;
        }
        base = base.replaceAll("\\s+", "");
        if (entry == null || !entry.pearls.containsKey(base)) {
            return base;
        }

        int suffix = 2;
        while (suffix < 10_000) {
            String candidate = base + suffix;
            if (!entry.pearls.containsKey(candidate)) {
                return candidate;
            }
            suffix++;
        }
        return null;
    }

    public void info(String message) {
        LOG.info(message);
    }

    // Check if first hotbar slot (slot 0) contains ender pearls
    private boolean hasPearlsInHotbarSlot0() {
        if (CACHE == null || CACHE.getPlayerCache() == null) {
            return false;
        }
        
        var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return false;
        }
        
        var itemStack = playerInventory.get(36); // Hotbar slot 0 = index 36
        if (itemStack == null) {
            return false;
        }
        
        return isEnderPearl(itemStack);
    }

    // Find pearls in inventory and move to hotbar slot 0
    private boolean ensurePearlsAvailable() {
        if (hasPearlsInHotbarSlot0()) {
            return true;
        }
        
        // Search for pearls in inventory
        int pearlSlot = findEnderPearlInInventory();
        if (pearlSlot == -1) {
            return false; // No pearls found
        }
        
        // Move pearls to hotbar slot 0
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new MoveToHotbarSlot(pearlSlot, MoveToHotbarAction.SLOT_1))
                .priority(1000)
                .build());
                info("Moved pearls to hotbar");
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to move pearls to hotbar slot 0", e);
            return false;
        }
    }

    // Drop one pearl from hotbar slot 0
    private void dropPearlFromHotbarSlot0() {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new DropItem(36, org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction.DROP_FROM_SELECTED))
                .priority(1000)
                .build());
        } catch (Exception e) {
            LOG.warn("Failed to drop pearl from hotbar slot 0", e);
        }
    }

    // Send out-of-pearls message to player
    private void sendOutOfPearlsMessage(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        
        String message = "I'm all out of pearls, can you give me some?";
        notifier.sendClientPacketAsync(ChatUtil.getWhisperChatPacket(playerName, message));
        info("Sent out-of-pearls message to " + playerName);
    }

    /**
     * Drops a new pearl to the player that just got teleported.
     * Will beg them for a restock if the bot ran out of pearls.
     * 
     * @param playerName    
     */
    public void handlePearlDropAfterLoad(String playerName) {
        if (!PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
            return;
        }
        
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        
        info("Attempting to drop pearl for " + playerName);
        
        if (ensurePearlsAvailable()) {
            dropPearlFromHotbarSlot0();
            info("Successfully dropped pearl for " + playerName);
        } else {
            sendOutOfPearlsMessage(playerName);
            info("No pearls available to drop for " + playerName + ". Begged them to drop me some.");
        }
    }

    private boolean isEnderPearl(Object itemStack) {
        if (itemStack == null) {
            return false;
        }
        
        try {
            // Check if it's an ItemStack and get the item ID
            int itemId = -1;
            if (itemStack instanceof org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack) {
                itemId = ((org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack) itemStack).getId();
            } else if (itemStack instanceof com.zenith.cache.data.inventory.Container) {
                // Handle Container.EMPTY_STACK case
                return false;
            }
            
            if (itemId == -1) {
                return false;
            }
            
            ItemData itemData = ItemRegistry.REGISTRY.get(itemId);
            if (itemData == null) {
                return false;
            }
            info(itemData.name());
            info("id: "+itemId);
            
            String itemName = itemData.name();
            
            return itemName != null && (
            "ENDER_PEARL".equals(itemName) ||
            "ender_pearl".equals(itemName) ||
            itemName.contains("ENDER_PEARL") ||
            itemName.contains("ender_pearl")
        );

        } catch (Exception e) {
            LOG.debug("Error checking if item is ender pearl", e);
            return false;
        }
    }

    // Helper method to find ender pearls in inventory
    private int findEnderPearlInInventory() {
        if (CACHE == null || CACHE.getPlayerCache() == null) {
            return -1;
        }
        
        var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return -1;
        }
        
        // Search through all inventory slots (9-44, excluding hotbar 36-44)
        for (int i = 9; i < playerInventory.size(); i++) {
            var itemStack = playerInventory.get(i);
            if (itemStack != null && isEnderPearl(itemStack)) {
                return i;
            }
        }
        
        return -1;
    }

    /**
     * Returns the amount of pearls a player has left in the stasis. 
     * 
     * @param ownerUuid UUID of the player we want to check the pearlcount for. 
     * @return  Pearlcount
     */
    public int countPresentPearls(UUID ownerUuid) {
        if (ownerUuid == null) return 0;
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls == null) return 0;
        
        int presentCount = 0;
        for (PearlPlusConfig.StoredPearl pearl : entry.pearls.values()) {
            if (isPearlPresent(pearl)) {
                presentCount++;
            }
        }
        
        return presentCount;
    }

    /**
     * Looks up the uuid from a playername. 
     * 
     * @param username
     * @return
     */
    public UUID getUuidFromUsername(String username) {
        if (username == null || username.isBlank()) return null;
        
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            UUID uuid = entry.getKey();
            PearlPlusConfig.PlayerPearls playerPearls = entry.getValue();
            if (playerPearls != null && username.equals(playerPearls.playerName)) {
                return uuid;
            }
        }
        return null;
    }
}
