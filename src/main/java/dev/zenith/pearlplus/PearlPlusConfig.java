package dev.zenith.pearlplus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();
    public static class AutoLoadConfig {
        public boolean enabled = true;
        public boolean allowNoiseAfterPearl = false;
        public Map<UUID, List<String>> allowed = new LinkedHashMap<>();
    }

    public final AutoDetectConfig autoDetect = new AutoDetectConfig();
    public static final class AutoDetectConfig {

    }
}