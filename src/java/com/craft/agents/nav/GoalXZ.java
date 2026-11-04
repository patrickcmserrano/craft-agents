package com.craft.agents.nav;

/**
 * Meta de aproximação horizontal bidimensional ignorando a altura Y.
 * Ideal para patrulhas em corredores, alinhamento em avenidas ou navegação por waypoints de praça.
 */
public record GoalXZ(int targetX, int targetZ, double range) implements Goal3D {
    public GoalXZ(int targetX, int targetZ) {
        this(targetX, targetZ, 1.0);
    }
    @Override
    public boolean isSatisfied(int x, int y, int z) {
        double dx = x - targetX;
        double dz = z - targetZ;
        return (dx * dx + dz * dz) <= (range * range);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x - targetX;
        double dz = z - targetZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Math.max(0.0, dist - range);
    }
}
