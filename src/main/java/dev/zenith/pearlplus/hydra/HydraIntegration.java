package dev.zenith.pearlplus.hydra;

import com.github.rfresh2.EventConsumer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rabbitmq.client.*;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import dev.zenith.pearlplus.PearlPlusConfig;
import dev.zenith.pearlplus.module.PearlManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CACHE;
import static dev.zenith.pearlplus.PearlPlusPlugin.*;

/**
 * Optional Hydra C2 integration for PearlPlus.
 *
 * <p>Activates automatically when both {@code HYDRA_RABBIT_URL} and
 * {@code HYDRA_AGENT_ID} environment variables are present. When active:
 * <ul>
 *   <li>Creates a private, ephemeral queue bound to the agent's command routing key so it
 *       receives copies of all commands without competing with hydra-zenith-agent's queue.</li>
 *   <li>Handles {@code PEARL_LOAD} commands — looks up the player's pearls and triggers a load.</li>
 *   <li>Publishes {@code agent.pearl.load} results back to {@code hydra.events}.</li>
 * </ul>
 *
 * <p>When env vars are absent the module registers but does nothing — zero impact on
 * standalone PearlPlus deployments.
 *
 * <h3>Queue Design</h3>
 * <p>This module declares a <em>separate</em> exclusive, auto-delete queue bound to the same
 * routing key ({@code agent.<id>.commands}) as hydra-zenith-agent's command queue. RabbitMQ
 * delivers a copy of each inbound command to every bound queue, so both consumers receive
 * all commands without round-robin competition. Unknown command types are silently ignored.
 *
 * <h3>Thread Model</h3>
 * <p>The RabbitMQ consumer thread enqueues {@link PearlLoadRequest} objects into a
 * {@link LinkedBlockingQueue}. The game tick handler drains the queue on ZenithProxy's
 * game thread before calling {@link PearlManager} methods, which interact with Baritone
 * and the entity cache and must run on the game thread.
 *
 * <h3>RabbitMQ Protocol</h3>
 * <b>Inbound command</b> (exchange: {@code hydra.commands},
 *   routing key: {@code agent.<id>.commands}):
 * <pre>{@code
 * {
 *   "type":      "PEARL_LOAD",
 *   "commandId": "<uuid>",
 *   "data": {
 *     "playerUUID": "<mojang-uuid>",
 *     "playerName": "<mc-username>"
 *   }
 * }
 * }</pre>
 *
 * <b>Outbound event</b> (exchange: {@code hydra.events},
 *   routing key: {@code agent.<id>.agent.pearl.load}):
 * <pre>{@code
 * {
 *   "agentId":   "<id>",
 *   "eventType": "agent.pearl.load",
 *   "ts":        <epoch-ms>,
 *   "data": {
 *     "commandId":  "<uuid>",
 *     "status":     "loading | no_pearls | not_found | error",
 *     "pearlCount": <n>,
 *     "pearlId":    "<id>",
 *     "playerName": "<mc-username>"
 *   }
 * }
 * }</pre>
 *
 * <p>Status semantics:
 * <ul>
 *   <li>{@code loading}   — pearl found; bot is pathfinding to the stasis chamber</li>
 *   <li>{@code no_pearls} — player has no pearls registered at this agent</li>
 *   <li>{@code not_found} — a pearl record exists but the requested ID cannot be resolved</li>
 *   <li>{@code error}     — bad request data (missing UUID, parse failure, etc.)</li>
 * </ul>
 */
public class HydraIntegration extends Module {

    // Exchange names must match hydra-zenith-agent's topology (see RabbitMQManager.java).
    private static final String EXCHANGE_EVENTS   = "hydra.events";
    private static final String EXCHANGE_COMMANDS = "hydra.commands";

    // PearlManager instance owned by this module — separate from AutoLoadModule's instance
    // so Baritone tasks submitted here don't interfere with whisper-driven loads.
    private final PearlManager pearlManager = new PearlManager(this);

    // Load requests flow: RabbitMQ consumer thread → queue → game tick thread.
    private final LinkedBlockingQueue<PearlLoadRequest> pendingLoads = new LinkedBlockingQueue<>();

    private volatile Connection rabbitConn;
    private volatile Channel    publishCh;
    private volatile boolean    hydraActive = false;
    private String agentId;

    // ── Module lifecycle ────────────────────────────────────────────────────────

    /** Always enabled so ZenithProxy keeps the event subscription alive. */
    @Override
    public boolean enabledSetting() {
        return true;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientBotTick.class, this::onGameTick));
    }

    /**
     * Called from {@link dev.zenith.pearlplus.PearlPlusPlugin#onLoad} after standard modules
     * are registered. Reads env vars and connects to RabbitMQ if both are present.
     */
    public void tryConnect() {
        String rabbitUrl = System.getenv("HYDRA_RABBIT_URL");
        agentId          = System.getenv("HYDRA_AGENT_ID");

        if (rabbitUrl == null || rabbitUrl.isBlank() || agentId == null || agentId.isBlank()) {
            LOG.info("[Hydra] HYDRA_RABBIT_URL / HYDRA_AGENT_ID not set — Hydra integration disabled");
            return;
        }

        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri(rabbitUrl);
            factory.setConnectionTimeout(5_000);
            factory.setRequestedHeartbeat(30);
            factory.setAutomaticRecoveryEnabled(true); // reconnects on network blips

            rabbitConn = factory.newConnection("pearlplus-hydra");

            // ── Publish channel ─────────────────────────────────────────────────
            publishCh = rabbitConn.createChannel();
            publishCh.exchangeDeclare(EXCHANGE_EVENTS, BuiltinExchangeType.TOPIC, true);

            // ── Command consumer channel ────────────────────────────────────────
            // We declare a private, auto-delete queue bound to the same routing key as
            // hydra-zenith-agent's command queue. RabbitMQ delivers a copy of each command
            // to every bound queue — so PearlPlus sees all commands without competing for
            // messages with the main agent consumer.
            Channel consumeCh = rabbitConn.createChannel();
            consumeCh.exchangeDeclare(EXCHANGE_COMMANDS, BuiltinExchangeType.TOPIC, true);

            String commandRoutingKey = "agent." + agentId + ".commands";
            // Exclusive + auto-delete: this queue exists only while PearlPlus is connected
            // and is automatically removed when the consumer disconnects.
            String pearlQueue = consumeCh.queueDeclare("", false, true, true, null).getQueue();
            consumeCh.queueBind(pearlQueue, EXCHANGE_COMMANDS, commandRoutingKey);
            consumeCh.basicConsume(pearlQueue, /*autoAck=*/true, this::onCommandDelivery, tag -> {});

            hydraActive = true;
            LOG.info("[Hydra] Connected to RabbitMQ — agent ID: {}, listening for PEARL_LOAD commands", agentId);
        } catch (Exception e) {
            LOG.warn("[Hydra] Failed to connect to RabbitMQ: {} — pearl C2 commands unavailable", e.getMessage());
        }
    }

    // ── RabbitMQ consumer (RabbitMQ consumer thread) ────────────────────────────

    /**
     * Receives a raw command delivery from the {@code hydra.commands} exchange.
     * Only {@code PEARL_LOAD} commands are enqueued; everything else is silently dropped
     * (they are intended for hydra-zenith-agent's handler).
     */
    private void onCommandDelivery(String consumerTag, Delivery delivery) {
        String raw = new String(delivery.getBody(), StandardCharsets.UTF_8);
        try {
            JsonObject msg  = JsonParser.parseString(raw).getAsJsonObject();
            String type     = msg.has("type") ? msg.get("type").getAsString() : "";

            if (!"PEARL_LOAD".equals(type)) return; // not our command

            String commandId  = msg.has("commandId") ? msg.get("commandId").getAsString() : "";
            JsonObject data   = msg.has("data") ? msg.getAsJsonObject("data") : new JsonObject();

            String uuidStr    = data.has("playerUUID") ? data.get("playerUUID").getAsString() : null;
            String playerName = data.has("playerName") ? data.get("playerName").getAsString() : null;

            if (uuidStr == null || uuidStr.isBlank()) {
                publishResult(commandId, "error", -1, null, playerName);
                return;
            }

            UUID playerUUID;
            try {
                playerUUID = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ex) {
                publishResult(commandId, "error", -1, null, playerName);
                return;
            }

            // Hand off to the game tick handler — PearlManager methods must run on game thread.
            pendingLoads.add(new PearlLoadRequest(commandId, playerUUID, playerName));
        } catch (Exception e) {
            LOG.warn("[Hydra] Failed to parse PEARL_LOAD command: {}", e.getMessage());
        }
    }

    // ── Game tick handler (ZenithProxy game thread) ─────────────────────────────

    private void onGameTick(ClientBotTick event) {
        if (!hydraActive) return;
        PearlLoadRequest req;
        while ((req = pendingLoads.poll()) != null) {
            processPearlLoad(req);
        }
    }

    /**
     * Resolves and triggers the pearl load for the given player UUID.
     * Runs on the game thread — safe to call PearlManager and Baritone.
     */
    private boolean isPlayerOnline(UUID playerUUID) {
        if (playerUUID == null) return false;
        return CACHE.getTabListCache().get(playerUUID).isPresent();
    }

    private void processPearlLoad(PearlLoadRequest req) {
        // Don't waste pearls on offline players — check the server tab list first.
        if (!isPlayerOnline(req.playerUUID())) {
            publishResult(req.commandId(), "player_offline", -1, null, req.playerName());
            return;
        }

        PearlPlusConfig.PlayerPearls playerEntry = PLUGIN_CONFIG.players.get(req.playerUUID());

        if (playerEntry == null || playerEntry.pearls.isEmpty()) {
            publishResult(req.commandId(), "no_pearls", 0, null, req.playerName());
            return;
        }

        // Resolve which pearl to load — honours default and autoDefaultToPresent setting.
        String pearlId = pearlManager.defaultPearlId(req.playerUUID());
        if (pearlId == null) {
            publishResult(req.commandId(), "not_found", 0, null, req.playerName());
            return;
        }

        PearlPlusConfig.StoredPearl pearl = playerEntry.pearls.get(pearlId);
        if (pearl == null) {
            publishResult(req.commandId(), "not_found", 0, pearlId, req.playerName());
            return;
        }

        // Count present pearls BEFORE the load so we can report an accurate post-load count.
        // One pearl will be consumed by the load, so subtract one (floor at 0).
        int presentNow     = pearlManager.countPresentPearls(req.playerUUID());
        int postLoadCount  = Math.max(0, presentNow - 1);

        // Immediately publish "loading" — the Discord side has a 10-12 s timeout and the
        // Baritone path can take several seconds. Sending "loading" first keeps the interaction
        // alive and lets the user know the request was accepted.
        publishResult(req.commandId(), "loading", postLoadCount, pearlId, req.playerName());

        // Trigger the actual load (Baritone pathfinding → trapdoor right-click).
        String displayName = req.playerName() != null ? req.playerName() : req.playerUUID().toString();
        pearlManager.loadPearl(pearl, displayName);
    }

    // ── Result publisher ────────────────────────────────────────────────────────

    /**
     * Publishes an {@code agent.pearl.load} event to {@code hydra.events}.
     *
     * <p>The JSON envelope matches the format produced by hydra-zenith-agent's
     * {@code RabbitMQManager.doPublish()} so the Go consumer can parse it with the same
     * {@code zenithEnvelope} struct used for all other agent events.
     *
     * <p>The routing key {@code agent.<id>.agent.pearl.load} follows the same pattern as all
     * other agent events (e.g. {@code agent.<id>.agent.status}, {@code agent.<id>.proxy.online})
     * so the Go side's wildcard binding {@code agent.<id>.#} captures it automatically.
     */
    private void publishResult(String commandId, String status, int pearlCount,
                               String pearlId, String playerName) {
        if (!hydraActive || publishCh == null) return;

        // Inner data object
        JsonObject data = new JsonObject();
        data.addProperty("commandId",  commandId  != null ? commandId  : "");
        data.addProperty("status",     status);
        data.addProperty("pearlCount", pearlCount);
        if (pearlId    != null) data.addProperty("pearlId",    pearlId);
        if (playerName != null) data.addProperty("playerName", playerName);

        // Outer envelope — matches RabbitMQManager.doPublish() format
        JsonObject envelope = new JsonObject();
        envelope.addProperty("agentId",   agentId);
        envelope.addProperty("eventType", "agent.pearl.load");
        envelope.addProperty("ts",        System.currentTimeMillis());
        envelope.add("data", data);

        String routingKey = "agent." + agentId + ".agent.pearl.load";
        byte[] body = envelope.toString().getBytes(StandardCharsets.UTF_8);

        try {
            publishCh.basicPublish(
                EXCHANGE_EVENTS, routingKey,
                false, false,
                new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(1) // non-persistent — transient result
                    .build(),
                body
            );
        } catch (IOException e) {
            LOG.warn("[Hydra] Failed to publish agent.pearl.load result: {}", e.getMessage());
        }
    }

    // ── Internal types ──────────────────────────────────────────────────────────

    private record PearlLoadRequest(String commandId, UUID playerUUID, String playerName) {}
}
