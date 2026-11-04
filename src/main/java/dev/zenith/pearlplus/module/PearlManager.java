package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.PearlPlusConfig;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlManager {
    private static final long CLAIM_RELEASE_TIMEOUT_MS = 5_000L;

    private static PendingLookClick pendingLookClick;
    private static final List<PendingClaimRelease> pendingClaimReleases =
            Collections.synchronizedList(new ArrayList<>());

    private final Module notifier;

    private record PendingLookClick(
            int x,
            int y,
            int z,
            int lookTicksRemaining,
            int lookLockTicksRemaining,
            PearlPlusConfig.StoredPearl pearl,
            String requesterName,
            UUID ownerUuid,
            BlockPos startPos,
            Module notifier
    ) {
    }

    private record PendingClaimRelease(
            UUID ownerUuid,
            String pearlId,
            int x,
            int z,
            long deadlineMs
    ) {
    }

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
        releaseStoredPearl(ownerUuid, pearlId);
    }

    private static void releaseStoredPearl(UUID ownerUuid, String pearlId) {
        if (ownerUuid == null || pearlId == null) {
            return;
        }
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return;
        entry.pearls.remove(pearlId);
        if (pearlId.equals(entry.defaultPearlId)) {
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
        StringBuilder candidates = PLUGIN_CONFIG.debug ? new StringBuilder() : null;

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
                    if (candidates != null) {
                        if (candidates.length() > 0) {
                            candidates.append('\n');
                        }
                        candidates.append(String.format("%s at [%d, %d, %d] distSq=%d", name, x, y, z, distSq));
                    }
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestPos = new BlockPos(x, y, z);
                    }
                }
            }
        }

        if (candidates != null) {
            if (candidates.length() == 0) {
                debug("No trapdoors in radius " + radius + " of stored pearl ["
                    + baseX + ", " + baseY + ", " + baseZ + "]");
            } else {
                debug("Trapdoor candidates near [" + baseX + ", " + baseY + ", " + baseZ + "]:\n" + candidates);
            }
        }

        return bestPos;
    }

    public void loadPearl(PearlPlusConfig.StoredPearl pearl, String requesterName, UUID ownerUuid) {
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

        BlockPos trapdoorPos = findNearestTrapdoorAround(pearl, 3);
        final int targetX;
        final int targetY;
        final int targetZ;
        if (trapdoorPos != null) {
            targetX = (int) trapdoorPos.x();
            targetY = (int) trapdoorPos.y();
            targetZ = (int) trapdoorPos.z();
            info("Loading pearl " + pearl.pearlId + " using trapdoor at ["
                    + targetX + ", " + targetY + ", " + targetZ + "]");
        } else {
            targetX = pearl.x;
            targetY = pearl.y;
            targetZ = pearl.z;
            info("No trapdoor detected for pearl " + pearl.pearlId
                    + ", falling back to stored block [" + targetX + ", " + targetY + ", " + targetZ + "]");
        }

        debugClickTarget(pearl, trapdoorPos, targetX, targetY, targetZ);
        GrimInteract.pathIntoReach(targetX, targetY, targetZ)
                .addExecutedListener(f -> startLookThenClick(
                        targetX, targetY, targetZ, pearl, requesterName, ownerUuid, current, notifier));

        notifier.discordAndIngameNotification(Embed.builder()
                .title("Loading Pearl")
                .addField("Pearl", pearl.pearlId, false)
                .primaryColor());
    }

    private static void startLookThenClick(
            int x,
            int y,
            int z,
            PearlPlusConfig.StoredPearl pearl,
            String requesterName,
            UUID ownerUuid,
            BlockPos startPos,
            Module notifier
    ) {
        pendingLookClick = new PendingLookClick(
                x, y, z,
                GrimInteract.LOOK_TICKS,
                GrimInteract.LOOK_LOCK_TIMEOUT_TICKS,
                pearl, requesterName, ownerUuid, startPos, notifier);
        GrimInteract.debug("In range of [" + x + ", " + y + ", " + z + "], looking for "
                + GrimInteract.LOOK_TICKS + " ticks before click");
    }

    public static void cancelPendingLookClick() {
        pendingLookClick = null;
    }

    public static void cancelPendingClaimReleases() {
        pendingClaimReleases.clear();
    }

    public static void tickPendingLookClick() {
        PendingLookClick pending = pendingLookClick;
        if (pending == null) {
            return;
        }
        GrimInteract.lookAt(pending.x(), pending.y(), pending.z());
        if (pending.lookTicksRemaining() > 0) {
            GrimInteract.debug("Looking at trapdoor [" + pending.x() + ", " + pending.y() + ", "
                    + pending.z() + "], " + pending.lookTicksRemaining() + " ticks left");
            pendingLookClick = new PendingLookClick(
                    pending.x(), pending.y(), pending.z(),
                    pending.lookTicksRemaining() - 1,
                    pending.lookLockTicksRemaining(),
                    pending.pearl(), pending.requesterName(), pending.ownerUuid(),
                    pending.startPos(), pending.notifier());
            return;
        }
        if (!GrimInteract.hasInteractableTarget(pending.x(), pending.y(), pending.z())) {
            pendingLookClick = null;
            pending.notifier().discordAndIngameNotification(Embed.builder()
                    .title("Pearl Load Failed")
                    .description("No interactable block at the trapdoor position, click aborted")
                    .errorColor());
            return;
        }
        if (GrimInteract.useItemOnIfLookingAt(pending.x(), pending.y(), pending.z())) {
            pendingLookClick = null;
            new PearlManager(pending.notifier())
                    .onPearlLoaded(pending.pearl(), pending.requesterName(), pending.ownerUuid(), pending.startPos());
            return;
        }
        if (pending.lookLockTicksRemaining() <= 0) {
            pendingLookClick = null;
            pending.notifier().discordAndIngameNotification(Embed.builder()
                    .title("Pearl Load Failed")
                    .description("Not looking at the trapdoor, click aborted")
                    .errorColor());
            return;
        }
        pendingLookClick = new PendingLookClick(
                pending.x(), pending.y(), pending.z(),
                0,
                pending.lookLockTicksRemaining() - 1,
                pending.pearl(), pending.requesterName(), pending.ownerUuid(),
                pending.startPos(), pending.notifier());
    }

    public static void tickPendingClaimReleases() {
        if (pendingClaimReleases.isEmpty()) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            return;
        }
        if (CACHE == null || CACHE.getEntityCache() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (pendingClaimReleases) {
            Iterator<PendingClaimRelease> iterator = pendingClaimReleases.iterator();
            while (iterator.hasNext()) {
                PendingClaimRelease pending = iterator.next();
                if (hasPearlEntityAt(pending.x(), pending.z())) {
                    if (now >= pending.deadlineMs()) {
                        iterator.remove();
                        LOG.info("[PearlPlus] Timed out waiting to release claim " + pending.pearlId()
                                + "; pearl still present at [" + pending.x() + ", " + pending.z() + "]");
                    }
                    continue;
                }
                iterator.remove();
                releaseStoredPearl(pending.ownerUuid(), pending.pearlId());
                LOG.info("[PearlPlus] Released pearl claim " + pending.pearlId() + " after load");
            }
        }
    }

    private void onPearlLoaded(PearlPlusConfig.StoredPearl pearl, String requesterName, UUID ownerUuid, BlockPos startPos) {
        var builder = Embed.builder()
                .title("Pearl Loaded!")
                .addField("Pearl ID", pearl.pearlId, false)
                .successColor();
        if (requesterName != null) {
            builder.addField("Requested By", requesterName, false);
        }
        notifier.discordAndIngameNotification(builder);

        releaseClaimAfterLoad(ownerUuid, pearl);

        int pearlCount = countEnderPearlsInInventory();
        if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
            handlePearlDropAfterLoad(requesterName);
        }
        boolean droppedOne = PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad
                && requesterName != null && !requesterName.isBlank()
                && pearlCount > 0;
        int remaining = droppedOne ? pearlCount - 1 : pearlCount;

        if (PLUGIN_CONFIG.restock.enabled && remaining <= 0) {
            MODULE.get(PearlRestockModule.class).requestAfterLoad(startPos);
            return;
        }

        if (PLUGIN_CONFIG.autoLoad.returnToStartPos) {
            BARITONE.pathTo(startPos.x(), startPos.y(), startPos.z())
                    .addExecutedListener(f2 -> notifier.discordAndIngameNotification(
                            Embed.builder()
                                    .description("Returned to start pos")
                                    .successColor()
                    ));
        }
    }

    private void releaseClaimAfterLoad(UUID ownerUuid, PearlPlusConfig.StoredPearl pearl) {
        if (pearl == null || pearl.pearlId == null || pearl.pearlId.isBlank()) {
            return;
        }
        UUID owner = ownerUuid != null ? ownerUuid : findOwnerUuid(pearl);
        if (owner == null) {
            info("Cannot release claim for " + pearl.pearlId + " after load: owner unknown");
            return;
        }
        if (CACHE != null && CACHE.getEntityCache() != null && !hasPearlEntityAt(pearl.x, pearl.z)) {
            removePearl(owner, pearl.pearlId);
            info("Released pearl claim " + pearl.pearlId + " after load");
            return;
        }
        synchronized (pendingClaimReleases) {
            pendingClaimReleases.removeIf(pending ->
                    owner.equals(pending.ownerUuid()) && pearl.pearlId.equals(pending.pearlId()));
            pendingClaimReleases.add(new PendingClaimRelease(
                    owner,
                    pearl.pearlId,
                    pearl.x,
                    pearl.z,
                    System.currentTimeMillis() + CLAIM_RELEASE_TIMEOUT_MS));
        }
        info("Pearl still present at [" + pearl.x + ", " + pearl.z + "]; will release claim "
                + pearl.pearlId + " when it despawns");
    }

    private UUID findOwnerUuid(PearlPlusConfig.StoredPearl pearl) {
        if (pearl == null || PLUGIN_CONFIG.players == null) {
            return null;
        }
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            if (entry.getValue() == null || entry.getValue().pearls == null) {
                continue;
            }
            if (entry.getValue().pearls.containsValue(pearl)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean hasPearlEntityAt(int blockX, int blockZ) {
        if (CACHE == null || CACHE.getEntityCache() == null) {
            return false;
        }
        return CACHE.getEntityCache().getEntities().values().stream()
                .anyMatch(entity -> entity.getEntityType() == EntityType.ENDER_PEARL
                        && Math.floor(entity.getX()) == blockX
                        && Math.floor(entity.getZ()) == blockZ);
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

    private void debug(String message) {
        if (!PLUGIN_CONFIG.debug) {
            return;
        }
        LOG.info("[debug] " + message);
    }

    private void debugClickTarget(
            PearlPlusConfig.StoredPearl pearl,
            BlockPos trapdoorPos,
            int clickX,
            int clickY,
            int clickZ
    ) {
        if (!PLUGIN_CONFIG.debug) {
            return;
        }
        String blockName = blockNameAt(clickX, clickY, clickZ);
        String trapdoorField = trapdoorPos == null
                ? "none (clicking stored pearl block)"
                : String.format("[%d, %d, %d]", (int) trapdoorPos.x(), (int) trapdoorPos.y(), (int) trapdoorPos.z());

        debug("Flipping trapdoor for " + pearl.pearlId
                + " stored=[" + pearl.x + ", " + pearl.y + ", " + pearl.z + "]"
                + " trapdoor=" + trapdoorField
                + " click=[" + clickX + ", " + clickY + ", " + clickZ + "] (" + blockName + ")");

        notifier.discordAndIngameNotification(Embed.builder()
                .title("PearlPlus Debug")
                .addField("Pearl", pearl.pearlId, true)
                .addField("Stored", String.format("[%d, %d, %d]", pearl.x, pearl.y, pearl.z), true)
                .addField("Trapdoor", trapdoorField, false)
                .addField("Clicking", String.format("[%d, %d, %d] %s", clickX, clickY, clickZ, blockName), false)
                .primaryColor());
    }

    private static String blockNameAt(int x, int y, int z) {
        if (CACHE == null || CACHE.getChunkCache() == null) {
            return "unknown";
        }
        var section = CACHE.getChunkCache().getChunkSection(x, y, z);
        if (section == null) {
            return "unloaded";
        }
        int stateId = section.getBlock(x & 15, y & 15, z & 15);
        var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        return block == null ? "unknown" : block.name();
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

    public boolean hasAnyEnderPearls() {
        return countEnderPearlsInInventory() > 0;
    }

    public int countEnderPearlsInInventory() {
        if (CACHE == null || CACHE.getPlayerCache() == null) {
            return 0;
        }
        var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return 0;
        }
        int count = 0;
        int end = Math.min(playerInventory.size(), 46);
        for (int i = 9; i < end; i++) {
            var itemStack = playerInventory.get(i);
            if (itemStack instanceof org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack stack
                    && stack.getId() == ItemRegistry.ENDER_PEARL.id()) {
                count += stack.getAmount();
            }
        }
        return count;
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
