package com.craft.agents.nav;

/**
 * Meta decoradora para operações de condução de entidades na guia (leash).
 * Modula a heurística e as regras cinemáticas de movimentação acoplada.
 */
public record GoalLeashedEscort(
    Goal3D innerGoal,
    String species,
    double maxSlack,
    double escortSpeed
) implements Goal3D {
    @Override
    public boolean isSatisfied(int x, int y, int z) {
        return innerGoal.isSatisfied(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return innerGoal.heuristic(x, y, z);
    }
}
