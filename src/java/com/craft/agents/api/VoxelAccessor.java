package com.craft.agents.api;

/**
 * Fronteira Dual-Backend para leitura rapida de mundo 3D sem acoplamento a classes Minecraft (CA-002).
 */
public interface VoxelAccessor {
    CollisionType getCollisionType(int x, int y, int z);
    double getFloorHeight(int x, int y, int z);
    boolean isHazard(int x, int y, int z);
    String getBlockId(int x, int y, int z);
    PortalGroup getPortalAt(int x, int y, int z);
}
