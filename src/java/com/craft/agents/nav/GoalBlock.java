package com.craft.agents.nav;

/**
 * Meta de alcance de um bloco exato (targetX, targetY, targetZ).
 */
public record GoalBlock(int targetX, int targetY, int targetZ) implements Goal3D {
    @Override
    public boolean isSatisfied(int x, int y, int z) {
        return x == targetX && y == targetY && z == targetZ;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
