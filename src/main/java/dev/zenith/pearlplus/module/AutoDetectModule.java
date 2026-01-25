package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.PearlPlusConfig;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;

import static com.zenith.Globals.*;
import static com.github.rfresh2.EventConsumer.of;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoDetectModule extends Module {
    private static final long STABLE_LOCATION_DURATION_MS = 3_000L;
    private static final long STORED_PEARL_REMOVAL_GRACE_MS = 60_000L;
    private static final int POSITION_HISTORY_LIMIT = 8;

    private final Map<Integer, TrackedPearl> trackedPearls = new HashMap<>();
    private final PearlManager pearlManager = new PearlManager(this);
    private final Set<Column> acknowledgedColumns = new HashSet<>();
    private boolean pendingReconnectGrace = false;
    private long suppressStoredPearlRemovalUntil = 0L;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoDetect.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
                of(ClientBotTick.class, event -> {
                    if (!PLUGIN_CONFIG.autoDetect.enabled) {
                        return;
                    }
                    scanForPearls();
                }),
                of(ClientBotTick.Stopped.class, event -> {
                    trackedPearls.clear();
                    pendingReconnectGrace = true;
                })
        );
    }

    @Override
    public void onEnable() {
        extendStoredPearlRemovalGracePeriod();
        markExistingPearls();
    }

    @Override
    public void onDisable() {
        trackedPearls.clear();
        pendingReconnectGrace = false;
    }

    public boolean isTemporaryModeEnabled() {
        return PLUGIN_CONFIG.autoDetect.temporaryMode;
    }

    public void onTemporaryModeToggle(boolean enabled) {
        info("PearlPlus Detect temp loader removal " + (enabled ? "enabled" : "disabled"));
    }

    public void markExistingPearls() {
        var cache = CACHE.getEntityCache();
        trackedPearls.clear();
        if (cache == null) {
            return;
        }

        Map<Integer, Entity> entities = cache.getEntities();
        long now = System.currentTimeMillis();

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.ENDER_PEARL) {
                continue;
            }

            BlockPosition position = blockPositionOf(entity);
            StoredPearlEntry storedEntry = findStoredPearlByColumn(position.x(), position.z()).orElse(null);
            OwnerInfo storedOwner = storedEntry != null ? storedEntry.ownerInfo() : null;
            OwnerInfo resolvedOwner = resolveOwnerInfo(entity, entities).orElse(null);
            OwnerInfo owner = selectOwner(resolvedOwner, storedOwner, null);

            TrackedPearl tracked = new TrackedPearl(position, owner, now);
            tracked.setPearlId(storedEntry != null ? storedEntry.pearl().pearlId : null);
            if (storedEntry != null) {
                acknowledgedColumns.add(columnOf(position));
            }
            trackedPearls.put(entity.getEntityId(), tracked);
        }
    }

    private void scanForPearls() {
        if (pendingReconnectGrace) {
            extendStoredPearlRemovalGracePeriod();
            pendingReconnectGrace = false;
        }

        var entityCache = CACHE.getEntityCache();
        if (entityCache == null) {
            trackedPearls.clear();
            return;
        }

        Map<Integer, Entity> entities = entityCache.getEntities();
        long now = System.currentTimeMillis();

        trackedPearls.entrySet().removeIf(entry -> {
            Entity entity = entities.get(entry.getKey());
            if (entity == null || entity.getEntityType() != EntityType.ENDER_PEARL) {
                handlePearlRemoval(entry.getValue());
                return true;
            }
            return false;
        });

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.ENDER_PEARL) {
                continue;
            }

            int entityId = entity.getEntityId();
            BlockPosition position = blockPositionOf(entity);
            StoredPearlEntry storedEntry = findStoredPearlByColumn(position.x(), position.z()).orElse(null);
            OwnerInfo storedOwner = storedEntry != null ? storedEntry.ownerInfo() : null;
            OwnerInfo resolvedOwner = resolveOwnerInfo(entity, entities).orElse(null);

            TrackedPearl tracked = trackedPearls.get(entityId);
            if (tracked == null) {
                OwnerInfo owner = selectOwner(resolvedOwner, storedOwner, null);
                tracked = new TrackedPearl(position, owner, now);
                tracked.setPearlId(storedEntry != null ? storedEntry.pearl().pearlId : null);
                trackedPearls.put(entityId, tracked);

                info(String.format(
                        "Detected new ender pearl (id=%d) at (%.2f, %.2f, %.2f) [block %d %d %d] thrown by %s",
                        entityId,
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        position.x(),
                        position.y(),
                        position.z(),
                        formatOwner(owner)
                ));
            } else {
                tracked.updatePosition(position, now);
                OwnerInfo owner = selectOwner(resolvedOwner, storedOwner, tracked.owner());
                tracked.setOwner(owner);
                if (storedEntry != null && storedEntry.pearl().pearlId != null) {
                    tracked.setPearlId(storedEntry.pearl().pearlId);
                }
            }
        }

        attemptAutoRegistration(now);
        checkStoredPearlsForMissingEntities(entities);
    }

    private void handlePearlRemoval(TrackedPearl trackedPearl) {
        info(String.format(
                "Ender pearl at block %d %d %d thrown by %s broke or despawned",
                trackedPearl.blockX(),
                trackedPearl.blockY(),
                trackedPearl.blockZ(),
                trackedPearl.ownerSummary()
        ));
        acknowledgedColumns.remove(columnOf(trackedPearl.registrationPosition()));
        handleTemporaryRemoval(trackedPearl);
    }

    private void handleTemporaryRemoval(TrackedPearl trackedPearl) {
        if (!isTemporaryModeEnabled()) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            return;
        }
        Optional<StoredPearlEntry> storedEntry = findStoredPearlByColumn(trackedPearl.blockX(), trackedPearl.blockZ());
        if (storedEntry.isEmpty()) {
            return;
        }
        trackedPearl.setOwner(selectOwner(trackedPearl.owner(), storedEntry.get().ownerInfo(), trackedPearl.owner()));
        trackedPearl.setPearlId(storedEntry.get().pearl().pearlId);
        if (trackedPearl.pearlId() == null || trackedPearl.owner() == null || trackedPearl.owner().uuid() == null) {
            return;
        }

        if (!withinRemovalRange(trackedPearl.registrationPosition())) {
            return;
        }

        sendRemovalWarning(trackedPearl);
        sendUnregisterWhisper(trackedPearl.owner(), trackedPearl.pearlId());
        pearlManager.removePearl(storedEntry.get().ownerUuid(), trackedPearl.pearlId());
    }

    private void attemptAutoRegistration(long now) {
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (!tracked.ownerHasName() || tracked.owner().uuid() == null) {
                if (!tracked.waitingForNameLogged()) {
                    info("Waiting for thrower's name/uuid before auto-registering loader");
                    tracked.markWaitingForNameLogged();
                }
                continue;
            } else {
                tracked.clearWaitingForNameLog();
            }

            if (!tracked.isStable(now)) {
                continue;
            }

            BlockPosition target = tracked.registrationPosition();
            Optional<StoredPearlEntry> existingStored = findStoredPearlByColumn(target.x(), target.z());
            if (existingStored.isPresent()) {
                StoredPearlEntry storedPearl = existingStored.get();
                if (isDifferentOwner(tracked.owner(), storedPearl.ownerInfo()) && tracked.ownerHasName() && !tracked.conflictNotified()) {
                    sendForeignOwnershipWhisper(tracked.owner().name(), storedPearl.ownerInfo());
                    tracked.markConflictNotified();
                }

                tracked.setPearlId(storedPearl.pearl().pearlId);
                tracked.setOwner(selectOwner(tracked.owner(), storedPearl.ownerInfo(), tracked.owner()));

                Column column = columnOf(target);

                if (!isDifferentOwner(tracked.owner(), storedPearl.ownerInfo())
                        && storedPearl.pearl().pearlId != null
                        && tracked.owner() != null
                        && tracked.owner().uuid() != null) {
                    pearlManager.recordPearl(
                            tracked.owner().uuid(),
                            tracked.owner().name(),
                            storedPearl.pearl().pearlId,
                            target.x(),
                            target.y(),
                            target.z());

                    acknowledgedColumns.add(column);
                }

                continue;
            }

            String pearlId = tracked.pearlId();
            if (pearlId == null || pearlId.isBlank()) {
                pearlId = pearlManager.nextAvailablePearlId(tracked.owner().uuid(), tracked.owner().name());
                if (pearlId == null) {
                    continue;
                }
                tracked.setPearlId(pearlId);
            }

            info(String.format(
                    "Registering pearl %s at %d %d %d for %s",
                    pearlId,
                    target.x(),
                    target.y(),
                    target.z(),
                    tracked.ownerSummary()
            ));

            pearlManager.recordPearl(tracked.owner().uuid(), tracked.owner().name(), pearlId, target.x(), target.y(), target.z());

            String ownerName = tracked.owner() != null ? tracked.owner().name() : null;
            if (ownerName != null && !ownerName.isBlank() && sendRegistrationWhisper(ownerName, pearlId)) {
                tracked.markRegistrationNotified();
            }
            acknowledgedColumns.add(columnOf(target));
            sendRegistrationNotification(pearlId, target, tracked);
        }
    }

    private boolean sendRegistrationWhisper(String ownerName, String pearlId) {
        if (ownerName == null || ownerName.isBlank() || pearlId == null || pearlId.isBlank()) {
            return false;
        }

        String message = determineBotName()
                .map(botName -> String.format("Pearl Registered. Load me with /w %s load %s", botName, pearlId))
                .orElse(String.format("Pearl Registered as %s.", pearlId));
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(ownerName, message));
        info(String.format(
                "Whispered registration instructions to %s for loader %s",
                ownerName,
                pearlId
        ));
        return true;
    }

    private void sendRegistrationNotification(String pearlId, BlockPosition position, TrackedPearl trackedPearl) {
        if (pearlId == null || pearlId.isBlank() || position == null) {
            return;
        }

        String ownerSummary = trackedPearl != null ? trackedPearl.ownerSummary() : "unknown";

        discordAndIngameNotification(Embed.builder()
                .title("Pearl Registered")
                .addField("Pearl", pearlId)
                .addField("Owner", ownerSummary)
                .addField("Position", String.format("%d %d %d", position.x(), position.y(), position.z()))
        );
    }

    private void sendForeignOwnershipWhisper(String throwerName, OwnerInfo storedOwner) {
        if (throwerName == null || throwerName.isBlank() || storedOwner == null || !storedOwner.hasName()) {
            return;
        }

        String message = String.format("Pearl spot already belongs to %s.", storedOwner.name());
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(throwerName, message));
        info(String.format("Notified %s that loader column is owned by %s", throwerName, storedOwner.describe()));
    }

    private Optional<String> determineBotName() {
        if (CACHE == null) {
            return Optional.empty();
        }
        var profileCache = CACHE.getProfileCache();
        if (profileCache == null) {
            return Optional.empty();
        }
        var profile = profileCache.getProfile();
        if (profile == null) {
            return Optional.empty();
        }
        String name = profile.getName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    private Optional<UUID> determineBotUuid() {
        if (CACHE == null) {
            return Optional.empty();
        }
        var profileCache = CACHE.getProfileCache();
        if (profileCache == null) {
            return Optional.empty();
        }
        var profile = profileCache.getProfile();
        if (profile == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profile.getId());
    }

    private String formatOwner(OwnerInfo owner) {
        return owner != null ? owner.describe() : "unknown";
    }

    private OwnerInfo selectOwner(OwnerInfo primary, OwnerInfo secondary, OwnerInfo fallback) {
        return mergeOwnerInfo(primary, mergeOwnerInfo(secondary, fallback));
    }

    private OwnerInfo mergeOwnerInfo(OwnerInfo preferred, OwnerInfo fallback) {
        if (preferred == null) {
            return fallback;
        }
        if (fallback == null) {
            return preferred;
        }
        UUID uuid = preferred.uuid() != null ? preferred.uuid() : fallback.uuid();
        String name = preferred.hasName() ? preferred.name() : fallback.name();
        return new OwnerInfo(uuid, name);
    }

    private boolean isDifferentOwner(OwnerInfo thrower, OwnerInfo storedOwner) {
        if (thrower == null || storedOwner == null) {
            return false;
        }
        if (thrower.uuid() != null && storedOwner.uuid() != null) {
            return !thrower.uuid().equals(storedOwner.uuid());
        }
        if (thrower.hasName() && storedOwner.hasName()) {
            return !thrower.name().equalsIgnoreCase(storedOwner.name());
        }
        return false;
    }

    private Optional<OwnerInfo> resolveOwnerInfo(Entity pearl, Map<Integer, Entity> entities) {
        Optional<OwnerInfo> resolved = resolveOwnerFromProjectileOwner(pearl, entities);
        if (resolved.isPresent() || !PLUGIN_CONFIG.autoDetect.distanceCheck) {
            return resolved;
        }
        return resolveOwnerFromClosestPlayer(pearl, entities);
    }

    private Optional<OwnerInfo> resolveOwnerFromProjectileOwner(Entity pearl, Map<Integer, Entity> entities) {
        var data = pearl.getObjectData();
        if (!(data instanceof ProjectileData projectileData)) {
            return Optional.empty();
        }

        int ownerEntityId = projectileData.getOwnerId();
        if (ownerEntityId <= 0) {
            return Optional.empty();
        }

        Entity ownerEntity = entities.get(ownerEntityId);
        UUID ownerUuid = ownerEntity != null ? ownerEntity.getUuid() : null;
        String ownerName = ownerUuid != null ? resolveOwnerName(ownerUuid).orElse(null) : null;

        if (ownerUuid == null && ownerName == null) {
            return Optional.empty();
        }
        return Optional.of(new OwnerInfo(ownerUuid, ownerName));
    }

    private Optional<OwnerInfo> resolveOwnerFromClosestPlayer(Entity pearl, Map<Integer, Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Optional.empty();
        }

        Optional<UUID> botUuid = determineBotUuid();
        Entity closest = null;
        double closestDistanceSq = 2.0;
        double pearlX = pearl.getX();
        double pearlY = pearl.getY();
        double pearlZ = pearl.getZ();

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.PLAYER) {
                continue;
            }
            UUID candidateUuid = entity.getUuid();
            if (botUuid.isPresent() && botUuid.get().equals(candidateUuid)) {
                continue;
            }

            double dx = entity.getX() - pearlX;
            double dy = entity.getY() - pearlY;
            double dz = entity.getZ() - pearlZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < closestDistanceSq) {
                closestDistanceSq = distanceSq;
                closest = entity;
            }
        }

        if (closest == null) {
            return Optional.empty();
        }

        UUID ownerUuid = closest.getUuid();
        String ownerName = ownerUuid != null ? resolveOwnerName(ownerUuid).orElse(null) : null;

        if (ownerUuid == null && ownerName == null) {
            return Optional.empty();
        }
        return Optional.of(new OwnerInfo(ownerUuid, ownerName));
    }

    private Optional<String> resolveOwnerName(UUID ownerUuid) {
        if (ownerUuid == null || CACHE == null) {
            return Optional.empty();
        }
        return CACHE.getTabListCache()
                .get(ownerUuid)
                .map(PlayerListEntry::getName)
                .filter(name -> !name.isBlank());
    }

    private BlockPosition blockPositionOf(Entity entity) {
        return new BlockPosition(
                (int) Math.floor(entity.getX()),
                (int) Math.round(entity.getY()), // Replaced floor with round, since in rare cases the pearl Y location was wrong.
                (int) Math.floor(entity.getZ())
        );
    }

    private void extendStoredPearlRemovalGracePeriod() {
        suppressStoredPearlRemovalUntil = System.currentTimeMillis() + STORED_PEARL_REMOVAL_GRACE_MS;
    }

    private void checkStoredPearlsForMissingEntities(Map<Integer, Entity> entities) {
        if (!isTemporaryModeEnabled()) {
            return;
        }
        if (PLUGIN_CONFIG.players.isEmpty()) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            return;
        }
        if (System.currentTimeMillis() < suppressStoredPearlRemovalUntil) {
            return;
        }

        Set<String> activeIds = new HashSet<>();
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (tracked.pearlId() != null) {
                activeIds.add(tracked.pearlId());
            }
        }

        for (var entry : new HashMap<>(PLUGIN_CONFIG.players).entrySet()) {
            UUID ownerUuid = entry.getKey();
            var playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null) {
                continue;
            }
            for (var pearlEntry : new HashMap<>(playerPearls.pearls).entrySet()) {
                PearlPlusConfig.StoredPearl stored = pearlEntry.getValue();
                if (stored == null) {
                    continue;
                }
                if (activeIds.contains(stored.pearlId)) {
                    continue;
                }

                boolean pearlPresent = false;
                for (Entity entity : entities.values()) {
                    if (entity.getEntityType() == EntityType.ENDER_PEARL) {
                        BlockPosition position = blockPositionOf(entity);
                        if (position.x() == stored.x && position.z() == stored.z) {
                            pearlPresent = true;
                            break;
                        }
                    }
                }
                if (pearlPresent) {
                    continue;
                }

                if (!withinRemovalRange(new BlockPosition(stored.x, stored.y, stored.z))) {
                    continue;
                }

                var trackedPearl = new TrackedPearl(new BlockPosition(stored.x, stored.y, stored.z), new OwnerInfo(ownerUuid, playerPearls.playerName), System.currentTimeMillis(), stored.pearlId);
                sendRemovalWarning(trackedPearl);
                sendUnregisterWhisper(trackedPearl.owner(), trackedPearl.pearlId());
                pearlManager.removePearl(ownerUuid, stored.pearlId);
            }
        }
    }

    private boolean withinRemovalRange(BlockPosition position) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }
        BlockPosition player = blockPositionOf(CACHE.getPlayerCache().getThePlayer());
        double dx = player.x() - position.x();
        double dy = player.y() - position.y();
        double dz = player.z() - position.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= PLUGIN_CONFIG.autoDetect.temporaryRemovalRange;
    }

    private Optional<StoredPearlEntry> findStoredPearlByColumn(int blockX, int blockZ) {
        return PLUGIN_CONFIG.players.entrySet().stream()
                .flatMap(entry -> entry.getValue().pearls.values().stream()
                        .filter(pearl -> pearl.x == blockX && pearl.z == blockZ)
                        .map(pearl -> new StoredPearlEntry(entry.getKey(), entry.getValue().playerName, pearl)))
                .max(Comparator.comparingInt(pearl -> pearl.pearl().y));
    }

    private Column columnOf(BlockPosition position) {
        if (position == null) {
            return null;
        }
        return new Column(position.x(), position.z());
    }

    private void sendRemovalWarning(TrackedPearl trackedPearl) {
        var builder = Embed.builder()
                .title("Pearl Removal Warning")
                .addField("Owner", trackedPearl.ownerSummary())
                .addField("Pearl", trackedPearl.pearlId() == null ? "unknown" : trackedPearl.pearlId())
                .addField("Position", String.format("%d %d %d", trackedPearl.blockX(), trackedPearl.blockY(), trackedPearl.blockZ()));
        discordAndIngameNotification(builder);
    }

    private void sendUnregisterWhisper(OwnerInfo owner, String pearlId) {
        if (owner == null || !owner.hasName()) {
            return;
        }
        String target = owner.name();
        if (target == null || target.isBlank()) {
            return;
        }

        String message = (pearlId == null || pearlId.isBlank())
                ? "A pearl was unregistered."
                : "Pearl " + pearlId + " was unregistered.";

        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(target, message));
        info(String.format("Whispered pearl removal notice to %s for %s", owner.describe(), pearlId == null ? "unknown pearl" : pearlId));
    }

    private record BlockPosition(int x, int y, int z) {
        boolean sameColumn(BlockPosition other) {
            return other != null && this.x == other.x && this.z == other.z;
        }
    }

    private record Column(int x, int z) { }

    private record OwnerInfo(UUID uuid, String name) {
        boolean hasName() {
            return name != null && !name.isBlank();
        }

        String describe() {
            if (hasName()) {
                return uuid != null ? name + " (" + uuid + ")" : name;
            }
            return uuid != null ? uuid.toString() : "unknown";
        }
    }

    private record StoredPearlEntry(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {
        OwnerInfo ownerInfo() {
            return new OwnerInfo(ownerUuid, ownerName);
        }
    }

    private static final class TrackedPearl {
        private BlockPosition position;
        private final Deque<BlockPosition> history = new ArrayDeque<>();
        private OwnerInfo owner;
        private String pearlId;
        private boolean waitingForNameLogged;
        private long lastMovedAt;
        private boolean conflictNotified;
        private boolean registrationNotified;
        private boolean moved;

        TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp) {
            this(position, owner, timestamp, null);
        }

        TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp, String pearlId) {
            this.position = position;
            this.owner = owner;
            this.lastMovedAt = timestamp;
            this.pearlId = pearlId;
            history.addLast(position);
        }

        void updatePosition(BlockPosition newPosition, long timestamp) {
            if (!newPosition.equals(this.position)) {
                this.position = newPosition;
                this.lastMovedAt = timestamp;
                this.moved = true;
                history.addLast(newPosition);
                while (history.size() > POSITION_HISTORY_LIMIT) {
                    history.removeFirst();
                }
            }
        }

        void setOwner(OwnerInfo owner) {
            this.owner = owner;
        }

        OwnerInfo owner() {
            return owner;
        }

        boolean ownerHasName() {
            return owner != null && owner.hasName();
        }

        String ownerSummary() {
            return owner != null ? owner.describe() : "unknown";
        }

        boolean waitingForNameLogged() {
            return waitingForNameLogged;
        }

        void markWaitingForNameLogged() {
            this.waitingForNameLogged = true;
        }

        void clearWaitingForNameLog() {
            this.waitingForNameLogged = false;
        }

        boolean conflictNotified() {
            return conflictNotified;
        }

        void markConflictNotified() {
            this.conflictNotified = true;
        }

        boolean registrationNotified() {
            return registrationNotified;
        }

        void markRegistrationNotified() {
            this.registrationNotified = true;
        }

        boolean hasMoved() {
            return moved;
        }

        void setPearlId(String pearlId) {
            this.pearlId = pearlId;
        }

        String pearlId() {
            return pearlId;
        }

        boolean hasPearlId() {
            return pearlId != null && !pearlId.isBlank();
        }

        boolean isStable(long now) {
            return now - lastMovedAt >= STABLE_LOCATION_DURATION_MS || hasBouncePattern();
        }

        BlockPosition registrationPosition() {
            if (hasBouncePattern()) {
                return highestRecentBouncePosition();
            }
            return position;
        }

        int blockX() {
            return position.x();
        }

        int blockY() {
            return position.y();
        }

        int blockZ() {
            return position.z();
        }

        private boolean hasBouncePattern() {
            if (history.size() < 5) {
                return false;
            }
            BlockPosition[] points = history.toArray(BlockPosition[]::new);
            BlockPosition latest = points[points.length - 1];
            BlockPosition previous = points[points.length - 2];
            BlockPosition third = points[points.length - 3];
            BlockPosition fourth = points[points.length - 4];
            BlockPosition fifth = points[points.length - 5];

            if (!latest.sameColumn(previous)) {
                return false;
            }
            if (latest.equals(previous)) {
                return false;
            }
            return latest.equals(third) && latest.equals(fifth) && previous.equals(fourth);
        }

        private BlockPosition highestRecentBouncePosition() {
            BlockPosition[] points = history.toArray(BlockPosition[]::new);
            BlockPosition latest = points[points.length - 1];
            BlockPosition previous = points[points.length - 2];
            return latest.y() >= previous.y() ? latest : previous;
        }
    }

}
