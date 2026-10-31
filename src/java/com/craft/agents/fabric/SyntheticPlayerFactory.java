package com.craft.agents.fabric;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Fabrica de ServerPlayerEntity sinteticos acoplados ao SilentEmbeddedChannel (CA-014).
 */
public final class SyntheticPlayerFactory {
    private SyntheticPlayerFactory() {}

    public static UUID offlineAgentUuid(String agentName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + agentName).getBytes(StandardCharsets.UTF_8));
    }

    public static Object spawnSyntheticPlayer(Object server, String agentName, String dimension, double x, double y, double z) {
        return null;
    }

    public static void removeSyntheticPlayer(Object server, Object playerEntity) {}
}
