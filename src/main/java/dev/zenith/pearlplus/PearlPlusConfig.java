package dev.zenith.pearlplus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();

    public static class AutoLoadConfig {
        public boolean enabled = true;
        public boolean allowNoiseAfterPearl = true;
        public Map<UUID, List<String>> allowed = new LinkedHashMap<>();
    }

    public final AutoDetectConfig autoDetect = new AutoDetectConfig();

    public static final class AutoDetectConfig {
        public boolean enabled = true;
        public boolean temporaryMode = true;
        public Map<String, StoredPearl> storedPearls = new LinkedHashMap<>();

        public static final class StoredPearl {
            public UUID playerUuid;
            public String playerName;
            public int x;
            public int y;
            public int z;
        }
    }
}