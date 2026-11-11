package com.craft.agents.fabric;

import java.util.Locale;

/**
 * Resultado da leitura da direcao do olhar (op LOOK).
 * Diferente do raycast: NAO depende de haver bloco a frente — devolve a direcao
 * real do olhar (vetor unitario) e os angulos de yaw/pitch que a produziram.
 */
public record SyntheticLookResult(
        boolean found,
        double dirX, double dirY, double dirZ,
        double yaw, double pitch) {

    public static SyntheticLookResult of(double[] dir, double yaw, double pitch) {
        return new SyntheticLookResult(true, dir[0], dir[1], dir[2], yaw, pitch);
    }

    public String toProtocolString() {
        if (!found) {
            return "ERR|NO_PLAYER";
        }
        return String.format(Locale.ROOT, "OK|%.4f|%.4f|%.4f|%.1f|%.1f",
                dirX, dirY, dirZ, yaw, pitch);
    }
}
