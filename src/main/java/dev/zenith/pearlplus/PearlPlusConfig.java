package dev.zenith.pearlplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();
    public final AutoDetectConfig autoDetect = new AutoDetectConfig();

    public String defaultPearlIdBase = "pearl";

    public final Map<UUID, PlayerPearls> players = new LinkedHashMap<>();

    public static class AutoLoadConfig {
        public boolean enabled = true;
        public boolean allowNoiseAfterPearl = true;
        public boolean returnToStartPos = true;
    }

    public static final class AutoDetectConfig {
        public boolean enabled = true;
        public boolean temporaryMode = false;
        public boolean twoBtwoTMode = false;
        public int temporaryRemovalRange = 32;
    }

    public static final class PlayerPearls {
        public String playerName;
        public String defaultPearlId;
        public Map<String, StoredPearl> pearls = new LinkedHashMap<>();
    }

    public static final class StoredPearl {
        public String pearlId;
        public int x;
        public int y;
        public int z;
    }
}
