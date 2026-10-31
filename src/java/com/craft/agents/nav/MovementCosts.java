package com.craft.agents.nav;

/**
 * Parametros de custo e limites geometricos do PathSolver3D (CA-024).
 */
public record MovementCosts(
    double walkCost,
    double diagonalCost,
    double jumpUpCost,
    double portalTraverseCost,
    double swimCost,
    int maxDropDown,
    boolean allowParkour,
    boolean allowPortals
) {
    public static MovementCosts defaults() {
        return new MovementCosts(1.0, Math.sqrt(2.0), 2.0, 2.5, 2.2, 3, false, true);
    }
}
