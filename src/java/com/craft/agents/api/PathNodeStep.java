package com.craft.agents.api;

/**
 * Passo individual na rota 3D gerada pelo PathSolver3D (CA-002, CA-024).
 */
public record PathNodeStep(
    int x,
    int y,
    int z,
    StepAction action,
    PortalGroup portal
) {
    public enum StepAction {
        WALK,
        DIAGONAL,
        JUMP_UP,
        DROP_DOWN,
        SWIM,
        CLIMB,
        PARKOUR,
        TRAVERSE_PORTAL
    }
}
