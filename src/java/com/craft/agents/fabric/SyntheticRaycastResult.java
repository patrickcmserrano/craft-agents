package com.craft.agents.fabric;

import java.util.Locale;

/**
 * Resultado do raycast geometrico in-JVM (CA-E3.1).
 */
public record SyntheticRaycastResult(
        boolean found,
        String blockId,
        int x, int y, int z,
        double distance) {

    public String toProtocolString() {
        if (found) {
            return String.format(Locale.ROOT, "OK|%s|%d|%d|%d|%.2f", blockId, x, y, z, distance);
        }
        return "ERR|NO_BLOCK_IN_SIGHT";
    }
}
