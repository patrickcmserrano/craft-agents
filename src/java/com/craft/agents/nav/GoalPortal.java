package com.craft.agents.nav;

/**
 * Meta estruturada para travessia de portões de cercados e portas.
 * Define a posição do portal, o vetor normal de aproximação ortogonal
 * e as âncoras externa e interna para navegação sem atrito em quinas.
 */
public record GoalPortal(
    int portalX, int portalY, int portalZ,
    double normalX, double normalZ,
    double insideAnchorX, double insideAnchorZ,
    double outsideAnchorX, double outsideAnchorZ
) implements Goal3D {
    @Override
    public boolean isSatisfied(int x, int y, int z) {
        double dx = x - insideAnchorX;
        double dz = z - insideAnchorZ;
        return (dx * dx + dz * dz) <= 1.5;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x - insideAnchorX;
        double dy = y - portalY;
        double dz = z - insideAnchorZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
