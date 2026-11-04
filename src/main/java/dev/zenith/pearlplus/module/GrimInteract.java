package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.RaycastHelper;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;
import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

final class GrimInteract {
    static final int LOOK_TICKS = 5;
    static final int LOOK_LOCK_TIMEOUT_TICKS = 20;

    private GrimInteract() {}

    static PathingRequestFuture pathIntoReach(int x, int y, int z) {
        int rangeSq = Math.max(2, (int) Math.pow(BOT.getBlockReachDistance() - 1, 2));
        return BARITONE.pathTo(new GoalNear(x, y, z, rangeSq));
    }

    static InputRequestFuture lookAt(int x, int y, int z) {
        var center = World.blockInteractionCenter(x, y, z);
        Vector2f rot = RotationHelper.rotationTo(center.x(), center.y(), center.z());
        return INPUTS.submit(InputRequest.builder()
                .owner(GrimInteract.class)
                .yaw(rot.getX())
                .pitch(rot.getY())
                .priority(Baritone.getPriority() + 1)
                .build());
    }

    static boolean hasInteractableTarget(int x, int y, int z) {
        var block = World.getBlock(x, y, z);
        if (block.isAir()) {
            return false;
        }
        var boxes = BLOCK_DATA.getInteractionBoxesFromBlockStateId(World.getBlockStateId(x, y, z));
        return boxes != null && !boxes.isEmpty();
    }

    static boolean isLookingAt(int x, int y, int z) {
        var ray = RaycastHelper.playerEyeRaycastThroughToBlockTarget(x, y, z);
        return ray.hit() && ray.x() == x && ray.y() == y && ray.z() == z;
    }

    /**
     * Sends UseItemOn for the target block only if the current look ray hits it.
     * Never sends UseItem, so a held ender pearl cannot be thrown.
     */
    static boolean useItemOnIfLookingAt(int x, int y, int z) {
        var ray = RaycastHelper.playerEyeRaycastThroughToBlockTarget(x, y, z);
        if (!ray.hit() || ray.x() != x || ray.y() != y || ray.z() != z) {
            debug("Not looking at [" + x + ", " + y + ", " + z + "], refusing click"
                    + (ray.hit() ? " (hit [" + ray.x() + ", " + ray.y() + ", " + ray.z() + "])" : " (miss)"));
            return false;
        }
        var client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) {
            return false;
        }
        try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
            client.send(new ServerboundUseItemOnPacket(
                    x, y, z,
                    ray.direction().mcpl(),
                    Hand.MAIN_HAND,
                    0f, 0f, 0f,
                    false,
                    false,
                    prediction.currentSequence()
            ));
        }
        client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        debug("Sent UseItemOn [" + x + ", " + y + ", " + z + "] face=" + ray.direction());
        return true;
    }

    static void debug(String message) {
        if (PLUGIN_CONFIG.debug) {
            LOG.info("[debug] " + message);
        }
    }
}
