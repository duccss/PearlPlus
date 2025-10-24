package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.PearlPlusConfig;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;

import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;
import static com.github.rfresh2.EventConsumer.of;

public class AutoDetectModule extends Module {
    private static final long STABLE_LOCATION_DURATION_MS = 3_000L;
    private static final long STORED_PEARL_REMOVAL_GRACE_MS = 60_000L;
    private static final int POSITION_HISTORY_LIMIT = 8;

    private final Map<Integer, TrackedPearl> trackedPearls = new HashMap<>();
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
            OwnerInfo storedOwner = storedEntry != null ? ownerInfoFromStored(storedEntry.config()) : null;
            OwnerInfo resolvedOwner = resolveOwnerInfo(entity, entities).orElse(null);
            OwnerInfo owner = selectOwner(resolvedOwner, storedOwner, null);

            TrackedPearl tracked = new TrackedPearl(position, owner, now);
            trackedPearls.put(entity.getEntityId(), tracked);

            Optional<BlockPosition> syncedPosition = trySyncConfiguredLoader(tracked, storedEntry);
            if (syncedPosition.isPresent()) {
                BlockPosition pos = syncedPosition.get();
                updateStoredPearlDetails(tracked.loaderId(), tracked.owner(), pos);
            }
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
                handlePearlRemoval(entry.getKey(), entry.getValue());
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
            OwnerInfo storedOwner = storedEntry != null ? ownerInfoFromStored(storedEntry.config()) : null;
            OwnerInfo resolvedOwner = resolveOwnerInfo(entity, entities).orElse(null);

            TrackedPearl tracked = trackedPearls.get(entityId);
            if (tracked == null) {
                OwnerInfo owner = selectOwner(resolvedOwner, storedOwner, null);
                tracked = new TrackedPearl(position, owner, now);
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

                Optional<BlockPosition> syncedPosition = trySyncConfiguredLoader(tracked, storedEntry);
                if (syncedPosition.isPresent()) {
                    BlockPosition pos = syncedPosition.get();
                    updateStoredPearlDetails(tracked.loaderId(), tracked.owner(), pos);
                }
            } else {
                tracked.updatePosition(position, now);
                OwnerInfo owner = selectOwner(resolvedOwner, storedOwner, tracked.owner());
                tracked.setOwner(owner);
                if (tracked.hasLoaderId()) {
                    updateStoredPearlDetails(tracked.loaderId(), tracked.owner(), tracked.registrationPosition());
                } else {
                    Optional<BlockPosition> syncedPosition = trySyncConfiguredLoader(tracked, storedEntry);
                    if (syncedPosition.isPresent()) {
                        BlockPosition pos = syncedPosition.get();
                        updateStoredPearlDetails(tracked.loaderId(), tracked.owner(), pos);
                    }
                }
            }
        }

        attemptAutoRegistration(now);
        checkStoredPearlsForMissingEntities(entities);
    }
    private void handlePearlRemoval(int entityId, TrackedPearl trackedPearl) {
        info(String.format(
                "Ender pearl (id=%d) at block %d %d %d thrown by %s broke or despawned",
                entityId,
                trackedPearl.blockX(),
                trackedPearl.blockY(),
                trackedPearl.blockZ(),
                trackedPearl.ownerSummary()
        ));
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
        if (!trackedPearl.isRegistered()) {
            return;
        }

        String loaderId = trackedPearl.loaderId();
        if (loaderId == null || loaderId.isBlank()) {
            loaderId = findStoredPearlByColumn(trackedPearl.blockX(), trackedPearl.blockZ())
                    .map(StoredPearlEntry::loaderId)
                    .orElse(null);
        }
        if (loaderId == null || loaderId.isBlank()) {
            info("Temp mode enabled but no loader id recorded for this pearl; skipping removal");
            return;
        }

        OwnerInfo owner = trackedPearl.owner();
        String ownerName = owner != null && owner.hasName() ? owner.name() : null;

        info(String.format(
                "Temp mode removing loader %s%s",
                loaderId,
                ownerName != null ? " for " + ownerName : ""
        ));

        executeCommand(String.format("pl del %s", loaderId));
        removeStoredPearlEntry(loaderId);
    }

    private void attemptAutoRegistration(long now) {
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (tracked.isRegistered()) {
                continue;
            }

            if (!tracked.hasLoaderId()) {
                Optional<BlockPosition> syncedPosition = trySyncConfiguredLoader(tracked,
                        findStoredPearlByColumn(tracked.blockX(), tracked.blockZ()).orElse(null));
                if (syncedPosition.isPresent()) {
                    updateStoredPearlDetails(tracked.loaderId(), tracked.owner(), syncedPosition.get());
                    continue;
                }
            }

            if (!tracked.ownerHasName()) {
                if (!tracked.waitingForNameLogged()) {
                    info("Waiting for thrower's name before auto-registering loader");
                    tracked.markWaitingForNameLogged();
                }
                continue;
            } else {
                tracked.clearWaitingForNameLog();
            }

            if (!tracked.isStable(now)) {
                continue;
            }

            String loaderId = tracked.loaderId();
            if (loaderId == null || loaderId.isBlank()) {
                loaderId = nextAvailableLoaderId(tracked.owner().name());
                if (loaderId == null) {
                    if (!tracked.idFailureLogged()) {
                        info(String.format(
                                "Unable to determine available pearl loader id for %s",
                                tracked.owner().name()
                        ));
                        tracked.markIdFailureLogged();
                    }
                    continue;
                }
                tracked.setLoaderId(loaderId);
                tracked.clearIdFailureLog();
            }

            BlockPosition target = tracked.registrationPosition();
            if (target.y() != tracked.blockY()) {
                info(String.format(
                        "Using block Y=%d instead of current Y=%d",
                        target.y(),
                        tracked.blockY()
                ));
            }

            info(String.format(
                    "Registering loader %s at %d %d %d for %s",
                    loaderId,
                    target.x(),
                    target.y(),
                    target.z(),
                    tracked.ownerSummary()
            ));

            registerPearl(tracked, target);
        }
    }

    private void registerPearl(TrackedPearl tracked, BlockPosition position) {
        String loaderId = tracked.loaderId();
        executeCommand(String.format(
                "pl add %s %d %d %d",
                loaderId,
                position.x(),
                position.y(),
                position.z()
        ));

        recordStoredPearl(loaderId, tracked.owner(), position);

        String ownerName = tracked.owner() != null ? tracked.owner().name() : null;
        if (ownerName != null && !ownerName.isBlank()) {
            sendRegistrationWhisper(ownerName, loaderId);
        }
        sendRegistrationNotification(loaderId, position, tracked);

        info(String.format(
                "Completed auto-registration for %s with loader %s",
                tracked.ownerSummary(),
                loaderId
        ));

        tracked.markRegistered();
    }

    private void sendRegistrationWhisper(String ownerName, String loaderId) {
        Optional<String> botName = determineBotName();
        if (botName.isEmpty()) {
            info(String.format(
                    "Unable to determine bot name to whisper %s about loader %s",
                    ownerName,
                    loaderId
            ));
            return;
        }

        String message = String.format(
                "Pearl Registered. Load me with /w %s load %s",
                botName.get(),
                loaderId
        );
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(ownerName, message));
        info(String.format(
                "Whispered registration instructions to %s for loader %s",
                ownerName,
                loaderId
        ));
    }

    private void sendRegistrationNotification(String loaderId, BlockPosition position, TrackedPearl trackedPearl) {
        if (loaderId == null || loaderId.isBlank() || position == null) {
            return;
        }

        String ownerSummary = trackedPearl != null ? trackedPearl.ownerSummary() : "unknown";

        discordAndIngameNotification(Embed.builder()
                .title("Pearl Registered")
                .addField("Loader", loaderId)
                .addField("Owner", ownerSummary)
                .addField("Position", String.format("%d %d %d", position.x(), position.y(), position.z()))
        );
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

    private Optional<BlockPosition> trySyncConfiguredLoader(TrackedPearl tracked, StoredPearlEntry storedEntry) {
        if (storedEntry != null) {
            tracked.setLoaderId(storedEntry.loaderId());
            tracked.markRegistered();
            tracked.setOwner(selectOwner(null, ownerInfoFromStored(storedEntry.config()), tracked.owner()));
            var config = storedEntry.config();
            return Optional.of(new BlockPosition(config.x, config.y, config.z));
        }

        Optional<LoaderInfo> loaderInfo = findConfiguredLoaderInfo(tracked.blockX(), tracked.blockZ());
        if (loaderInfo.isEmpty()) {
            return Optional.empty();
        }

        LoaderInfo info = loaderInfo.get();
        tracked.setLoaderId(info.id());
        tracked.markRegistered();
        return Optional.of(new BlockPosition(info.x(), info.y(), info.z()));
    }
    private OwnerInfo ownerInfoFromStored(PearlPlusConfig.AutoDetectConfig.StoredPearl stored) {
        if (stored == null) {
            return null;
        }
        return new OwnerInfo(stored.playerUuid, stored.playerName);
    }

    private Optional<StoredPearlEntry> findStoredPearlByLoaderId(String loaderId) {
        if (loaderId == null || loaderId.isBlank()) {
            return Optional.empty();
        }
        var stored = PLUGIN_CONFIG.autoDetect.storedPearls.get(loaderId);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredPearlEntry(loaderId, stored));
    }

    private Optional<StoredPearlEntry> findStoredPearlByColumn(int blockX, int blockZ) {
        return PLUGIN_CONFIG.autoDetect.storedPearls.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().x == blockX && entry.getValue().z == blockZ)
                .max(Map.Entry.comparingByValue((a, b) -> Integer.compare(a.y, b.y)))
                .map(entry -> new StoredPearlEntry(entry.getKey(), entry.getValue()));
    }

    private void recordStoredPearl(String loaderId, OwnerInfo ownerInfo, BlockPosition position) {
        updateStoredPearlDetails(loaderId, ownerInfo, position);
        if (ownerInfo != null && ownerInfo.uuid() != null) {
            addAllowedPearl(ownerInfo.uuid(), loaderId);
        }
    }

    private void updateStoredPearlDetails(String loaderId, OwnerInfo ownerInfo, BlockPosition position) {
        if (loaderId == null || loaderId.isBlank()) {
            return;
        }
        var stored = PLUGIN_CONFIG.autoDetect.storedPearls.computeIfAbsent(loaderId, id -> new PearlPlusConfig.AutoDetectConfig.StoredPearl());
        if (position != null) {
            stored.x = position.x();
            stored.y = position.y();
            stored.z = position.z();
        }
        if (ownerInfo != null) {
            if (ownerInfo.uuid() != null) {
                stored.playerUuid = ownerInfo.uuid();
                addAllowedPearl(ownerInfo.uuid(), loaderId);
            }
            if (ownerInfo.hasName()) {
                stored.playerName = ownerInfo.name();
            }
        }
    }

    private Optional<PearlPlusConfig.AutoDetectConfig.StoredPearl> removeStoredPearlEntry(String loaderId) {
        if (loaderId == null || loaderId.isBlank()) {
            return Optional.empty();
        }
        var removed = PLUGIN_CONFIG.autoDetect.storedPearls.remove(loaderId);
        if (removed != null && removed.playerUuid != null) {
            removeAllowedPearl(removed.playerUuid, loaderId);
        }
        return Optional.ofNullable(removed);
    }

    private void addAllowedPearl(UUID playerUuid, String loaderId) {
        if (playerUuid == null || loaderId == null || loaderId.isBlank()) {
            return;
        }
        var allowed = PLUGIN_CONFIG.autoLoad.allowed.computeIfAbsent(playerUuid, uuid -> new ArrayList<>());
        if (!allowed.contains(loaderId)) {
            allowed.add(loaderId);
        }
    }

    private void removeAllowedPearl(UUID playerUuid, String loaderId) {
        if (playerUuid == null || loaderId == null || loaderId.isBlank()) {
            return;
        }
        var allowed = PLUGIN_CONFIG.autoLoad.allowed.get(playerUuid);
        if (allowed == null) {
            return;
        }
        allowed.removeIf(loaderId::equals);
        if (allowed.isEmpty()) {
            PLUGIN_CONFIG.autoLoad.allowed.remove(playerUuid);
        }
    }

    private void checkStoredPearlsForMissingEntities(Map<Integer, Entity> entities) {
        if (!isTemporaryModeEnabled()) {
            return;
        }
        if (PLUGIN_CONFIG.autoDetect.storedPearls.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() < suppressStoredPearlRemovalUntil) {
            return;
        }

        Set<String> activeLoaderIds = new HashSet<>();
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (tracked.hasLoaderId()) {
                activeLoaderIds.add(tracked.loaderId());
            }
        }

        for (var entry : new HashMap<>(PLUGIN_CONFIG.autoDetect.storedPearls).entrySet()) {
            String loaderId = entry.getKey();
            var stored = entry.getValue();
            if (stored == null) {
                PLUGIN_CONFIG.autoDetect.storedPearls.remove(loaderId);
                continue;
            }
            if (activeLoaderIds.contains(loaderId)) {
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

            Optional<LoaderInfo> loaderInfo = findConfiguredLoaderInfo(stored.x, stored.z);
            if (loaderInfo.isEmpty()) {
                removeStoredPearlEntry(loaderId);
                continue;
            }

            info(String.format(
                    "Temp mode removing loader %s at %d %d %d (no pearl detected)",
                    loaderId,
                    stored.x,
                    stored.y,
                    stored.z
            ));

            executeCommand(String.format("pl del %s", loaderId));
            removeStoredPearlEntry(loaderId);
        }
    }

    private Optional<LoaderInfo> findConfiguredLoaderInfo(int blockX, int blockZ) {
        var config = CONFIG;
        if (config == null || config.client == null || config.client.extra == null) {
            return Optional.empty();
        }
        var loader = config.client.extra.pearlLoader;
        if (loader == null || loader.pearls == null || loader.pearls.isEmpty()) {
            return Optional.empty();
        }
        return loader.pearls.stream()
                .filter(pearl -> pearl != null && pearl.x() == blockX && pearl.z() == blockZ && pearl.id() != null && !pearl.id().isBlank())
                .max((a, b) -> Integer.compare(a.y(), b.y()))
                .map(pearl -> new LoaderInfo(pearl.id(), pearl.x(), pearl.y(), pearl.z()));
    }

    private String nextAvailableLoaderId(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        String base = playerName.replaceAll("\\s+", "");
        Set<String> usedIds = gatherUsedLoaderIds();
        int suffix = 1;
        while (suffix < 10_000) {
            String candidate = base + suffix;
            if (!usedIds.contains(candidate)) {
                usedIds.add(candidate);
                return candidate;
            }
            suffix++;
        }
        return null;
    }

    private Set<String> gatherUsedLoaderIds() {
        Set<String> ids = new HashSet<>();

        var config = CONFIG;
        if (config != null && config.client != null && config.client.extra != null && config.client.extra.pearlLoader != null && config.client.extra.pearlLoader.pearls != null) {
            config.client.extra.pearlLoader.pearls.stream()
                    .map(pearl -> pearl.id())
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(ids::add);
        }

        PLUGIN_CONFIG.autoLoad.allowed.values().forEach(ids::addAll);
        ids.addAll(PLUGIN_CONFIG.autoDetect.storedPearls.keySet());

        return ids;
    }

    private void executeCommand(String command) {
        info("Executing command: " + command);
        var ctx = CommandContext.create(command, AutoDetectCommandSource.INSTANCE);
        COMMAND.execute(ctx);
        Embed embed = ctx.getEmbed();
        if (embed != null && embed.isTitlePresent()) {
            info("Response: " + embed.title());
        }
    }

    private Optional<OwnerInfo> resolveOwnerInfo(Entity pearl, Map<Integer, Entity> entities) {
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
                (int) Math.floor(entity.getY()),
                (int) Math.floor(entity.getZ())
        );
    }

    private void extendStoredPearlRemovalGracePeriod() {
        suppressStoredPearlRemovalUntil = System.currentTimeMillis() + STORED_PEARL_REMOVAL_GRACE_MS;
    }
    private static final class TrackedPearl {
        private BlockPosition position;
        private final Deque<BlockPosition> history = new ArrayDeque<>();
        private OwnerInfo owner;
        private String loaderId;
        private boolean registered;
        private boolean waitingForNameLogged;
        private boolean idFailureLogged;
        private long lastMovedAt;

        TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp) {
            this.position = position;
            this.owner = owner;
            this.lastMovedAt = timestamp;
            history.addLast(position);
        }

        void updatePosition(BlockPosition newPosition, long timestamp) {
            if (!newPosition.equals(this.position)) {
                this.position = newPosition;
                this.lastMovedAt = timestamp;
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

        boolean idFailureLogged() {
            return idFailureLogged;
        }

        void markIdFailureLogged() {
            this.idFailureLogged = true;
        }

        void clearIdFailureLog() {
            this.idFailureLogged = false;
        }

        void setLoaderId(String loaderId) {
            this.loaderId = loaderId;
        }

        String loaderId() {
            return loaderId;
        }

        boolean hasLoaderId() {
            return loaderId != null && !loaderId.isBlank();
        }

        void markRegistered() {
            this.registered = true;
        }

        boolean isRegistered() {
            return registered;
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

    private record BlockPosition(int x, int y, int z) {
        boolean sameColumn(BlockPosition other) {
            return other != null && this.x == other.x && this.z == other.z;
        }
    }

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

    private record LoaderInfo(String id, int x, int y, int z) {
    }

    private record StoredPearlEntry(String loaderId, PearlPlusConfig.AutoDetectConfig.StoredPearl config) {
    }

    private static final class AutoDetectCommandSource implements CommandSource {
        private static final AutoDetectCommandSource INSTANCE = new AutoDetectCommandSource();

        @Override
        public String name() {
            return "PearlPlus AutoDetect";
        }

        @Override
        public boolean validateAccountOwner(CommandContext ctx) {
            return false;
        }

        @Override
        public void logEmbed(CommandContext ctx, Embed embed) {
        }
    }
}