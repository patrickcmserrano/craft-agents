package com.craft.agents.nav;

/**
 * Meta de aproximação até um raio tridimensional de (targetX, targetY, targetZ).
 * Ideal para alcançar baús, mobs ou pontos de alimentação em margens de rios sem exigir
 * pouso no bloco exato.
 */
public record GoalNear(int targetX, int targetY, int targetZ, double range) implements Goal3D {
    @Override
    public boolean isSatisfied(int x, int y, int z) {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        return (dx * dx + dy * dy + dz * dz) <= (range * range);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(0.0, dist - range);
    }
}
