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

    // find the nearest trapdoor block around the stored pearl location.
    private BlockPos findNearestTrapdoorAround(final PearlPlusConfig.StoredPearl pearl, final int radius) {
        if (pearl == null || CACHE == null || CACHE.getChunkCache() == null) {
            return null;
        }

        var chunkCache = CACHE.getChunkCache();

        final int baseX = pearl.x;
        final int baseY = pearl.y;
        final int baseZ = pearl.z;

        BlockPos bestPos = null;
        int bestDistSq = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            final int x = baseX + dx;

            for (int dy = -radius; dy <= radius; dy++) {
                final int y = baseY + dy;

                for (int dz = -radius; dz <= radius; dz++) {
                    final int z = baseZ + dz;

                    var section = chunkCache.getChunkSection(x, y, z);
                    if (section == null) {
                        continue;
                    }

                    int relX = x & 15;
                    int relY = y & 15;
                    int relZ = z & 15;

                    int stateId = section.getBlock(relX, relY, relZ);
                    if (stateId == 0) {
                        continue; // air / unknown, skip quickly
                    }

                    var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
                    if (block == null) {
                        continue;
                    }

                    String name = block.name();
                    if (!name.endsWith("_trapdoor")) {
                        continue;
                    }

                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestPos = new BlockPos(x, y, z);
                    }
                }
            }
        }

        return bestPos;
    }

    private BlockPos findAdjacentWalkableBlock(final BlockPos trapdoorPos) {
        if (trapdoorPos == null || CACHE == null || CACHE.getChunkCache() == null) {
            info("Walkable search aborted: missing trapdoorPos or chunk cache");
            return null;
        }

        var chunkCache = CACHE.getChunkCache();

        final int tx = (int) trapdoorPos.x();
        final int ty = (int) trapdoorPos.y();
        final int tz = (int) trapdoorPos.z();

        // check 4 cardinal neighbours around the trapdoor
        int[][] offsets = {
                { 1, 0 },
                { -1, 0 },
                { 0, 1 },
                { 0, -1 }
        };

        for (int[] off : offsets) {
            int x = tx + off[0];
            int z = tz + off[1];
            int groundY = ty - 1; // assume floor is one below trapdoor

            // ground block
            var groundSection = chunkCache.getChunkSection(x, groundY, z);
            if (groundSection == null) {
                continue;
            }

            int relX = x & 15;
            int relY = groundY & 15;
            int relZ = z & 15;

            int groundStateId = groundSection.getBlock(relX, relY, relZ);
            if (groundStateId == 0) {
                // air / unknown not walkable
                continue;
            }

            var groundBlock = BLOCK_DATA.getBlockDataFromBlockStateId(groundStateId);
            if (groundBlock == null) {
                continue;
            }

            String groundName = groundBlock.name();

            if (groundName.contains("water")
                    || groundName.contains("lava")
                    || groundName.endsWith("_trapdoor")
                    || groundName.contains("ladder")
                    || groundName.contains("vine")
                    || groundName.contains("scaffolding")) {
                continue;
            }

            // check the space where the player will stand
            int headY = groundY + 1;
            var headSection = chunkCache.getChunkSection(x, headY, z);
            if (headSection == null) {
                continue;
            }

            int headStateId = headSection.getBlock(x & 15, headY & 15, z & 15);
            if (headStateId != 0) {
                var headBlock = BLOCK_DATA.getBlockDataFromBlockStateId(headStateId);
                if (headBlock != null) {
                    String headName = headBlock.name();
                    if (!headName.contains("air")) {
                        continue;
                    }
                }
            }

            BlockPos walkPos = new BlockPos(x, groundY, z);
            info("Found adjacent walkable block near trapdoor at ["
                    + tx + ", " + ty + ", " + tz + "] -> ["
                    + walkPos.x() + ", " + walkPos.y() + ", " + walkPos.z() + "]");
            return walkPos;
        }

        info("No adjacent walkable block found around trapdoor at ["
                + tx + ", " + ty + ", " + tz + "]");
        return null;
    }

    public void loadPearl(PearlPlusConfig.StoredPearl pearl, String requesterName) {
        if (pearl == null) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Can't Load Pearl")
                    .description("Bot is not online")
                    .errorColor());
            return;
        }
        if (proxy.hasActivePlayer()) {
            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Can't Load Pearl")
                    .description("Player is controlling")
                    .errorColor());
            return;
        }

        // make sure there is a pearl to drop for the player before walking.
        if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
            ensurePearlsAvailable();
        }

        // remember where we started so we can go back later.
        BlockPos current = CACHE.getPlayerCache().getThePlayer().blockPos();

        // locate the trapdoor for this pearl
        BlockPos trapdoorPos = findNearestTrapdoorAround(pearl, 3); // radius 3 is usually plenty

        if (trapdoorPos == null) {
            info("No trapdoor detected for pearl " + pearl.pearlId
                    + ", falling back to original behaviour (click stored block)");
            // fall back
            final int targetX = pearl.x;
            final int targetY = pearl.y;
            final int targetZ = pearl.z;
            final BlockPos startPos = current;

            BARITONE.rightClickBlock(targetX, targetY, targetZ)
                    .addExecutedListener(f -> {
                        var builder = Embed.builder()
                                .title("Pearl Loaded!")
                                .addField("Pearl ID", pearl.pearlId, false)
                                .successColor();
                        if (requesterName != null) {
                            builder.addField("Requested By", requesterName, false);
                        }
                        notifier.discordAndIngameNotification(builder);

                        if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
                            handlePearlDropAfterLoad(requesterName);
                        }

                        if (PLUGIN_CONFIG.autoLoad.returnToStartPos) {
                            BARITONE.pathTo(startPos.x(), startPos.z())
                                    .addExecutedListener(f2 -> notifier.discordAndIngameNotification(
                                            Embed.builder()
                                                    .description("Returned to start pos")
                                                    .successColor()
                                    ));
                        }
                    });

            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Loading Pearl")
                    .addField("Pearl", pearl.pearlId, false)
                    .primaryColor());
            return;
        }

        int trapX = (int) trapdoorPos.x();
        int trapY = (int) trapdoorPos.y();
        int trapZ = (int) trapdoorPos.z();
        info("Loading pearl " + pearl.pearlId + " using trapdoor at ["
                + trapX + ", " + trapY + ", " + trapZ + "]");

        // 2) Find a safe adjacent floor block to stand on
        BlockPos walkPos = findAdjacentWalkableBlock(trapdoorPos);

        int pathX = trapX;
        int pathZ = trapZ;

        if (walkPos != null) {
            pathX = (int) walkPos.x();
            pathZ = (int) walkPos.z();
            info("Pathing to adjacent walkable block [" + pathX + ", " + walkPos.y() + ", " + pathZ + "]"
                    + " and then clicking trapdoor");
        } else {
            info("No adjacent walkable block found, pathing directly to trapdoor column [" + pathX + ", " + pathZ + "]");
        }

        final int targetX = trapX;
        final int targetY = trapY;
        final int targetZ = trapZ;
        final int pathTargetX = pathX;
        final int pathTargetZ = pathZ;
        final BlockPos startPos = current;

        // path to the walkable block and right-click the trapdoor
        BARITONE.pathTo(pathTargetX, pathTargetZ)
                .addExecutedListener(pathFuture -> {
                    // once pathing attempt is "done", try the click regardless of success/failure.
                    BARITONE.rightClickBlock(targetX, targetY, targetZ)
                            .addExecutedListener(f -> {
                                var builder = Embed.builder()
                                        .title("Pearl Loaded!")
                                        .addField("Pearl ID", pearl.pearlId, false)
                                        .successColor();
                                if (requesterName != null) {
                                    builder.addField("Requested By", requesterName, false);
                                }
                                notifier.discordAndIngameNotification(builder);

                                // drop a pearl when loaded.
                                if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
                                    handlePearlDropAfterLoad(requesterName);
                                }

                                // return to start position.
                                if (PLUGIN_CONFIG.autoLoad.returnToStartPos) {
                                    BARITONE.pathTo(startPos.x(), startPos.z())
                                            .addExecutedListener(f2 -> notifier.discordAndIngameNotification(
                                                    Embed.builder()
                                                            .description("Returned to start pos")
                                                            .successColor()
                                            ));
                                }
                            });
                });

        notifier.discordAndIngameNotification(Embed.builder()
                .title("Loading Pearl")
                .addField("Pearl", pearl.pearlId, false)
                .primaryColor());
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
