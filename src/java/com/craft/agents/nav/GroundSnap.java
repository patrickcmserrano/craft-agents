package com.craft.agents.nav;

import com.craft.agents.api.VoxelAccessor;

/**
 * Acha o y em pe correto de uma coluna (CA-063), usando a mesma regra de
 * "partida valida" da recuperacao de partida do A* (CA-062).
 */
public final class GroundSnap {
    private GroundSnap() {}

    /**
     * Testa os offsets 0, +1, -1, +2, -2, ... ate +up e -down e devolve o primeiro y valido,
     * ou Integer.MIN_VALUE se nenhum servir.
     */
    public static int findStandableY(VoxelAccessor world, int x, int y, int z, int up, int down) {
        MovementCapabilities caps = MovementCapabilities.defaults();
        if (PathSolver3D.isValidStart(world, caps, x, y, z)) {
            return y;
        }
        int max = Math.max(up, down);
        for (int d = 1; d <= max; d++) {
            if (d <= up && PathSolver3D.isValidStart(world, caps, x, y + d, z)) {
                return y + d;
            }
            if (d <= down && PathSolver3D.isValidStart(world, caps, x, y - d, z)) {
                return y - d;
            }
        }
        return Integer.MIN_VALUE;
    }
}
