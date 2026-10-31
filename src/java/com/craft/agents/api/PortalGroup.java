package com.craft.agents.api;

/**
 * Representa um portal semantico atomico: porta simples, porta dupla ou portoes empilhados y / y+1 (CA-002, CA-023).
 */
public record PortalGroup(
    String id,
    long[] packedBlockPositions,
    double centerX,
    double centerY,
    double centerZ,
    double normalX,
    double normalZ,
    boolean canBotToggle,
    boolean currentlyOpen
) {}
