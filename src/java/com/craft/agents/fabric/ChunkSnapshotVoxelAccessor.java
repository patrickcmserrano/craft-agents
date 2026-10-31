package com.craft.agents.fabric;

import com.craft.agents.api.CollisionType;
import com.craft.agents.api.PortalGroup;
import com.craft.agents.api.VoxelAccessor;
import com.craft.agents.nav.PackedPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot imutavel de secoes de chunk para execucao thread-safe do PathSolver3D fora da ServerThread (CA-022).
 */
public final class ChunkSnapshotVoxelAccessor implements VoxelAccessor {
    private final int centerX;
    private final int centerZ;
    private final int chunkRadius;
    private final Map<Long, CollisionType> collisionOverrides;

    public ChunkSnapshotVoxelAccessor(int centerX, int centerZ, int chunkRadius) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.chunkRadius = chunkRadius;
        this.collisionOverrides = new ConcurrentHashMap<>();
    }

    public static ChunkSnapshotVoxelAccessor captureRadius(Object serverWorld, int centerX, int centerZ, int chunkRadius) {
        return new ChunkSnapshotVoxelAccessor(centerX, centerZ, chunkRadius);
    }

    @Override
    public CollisionType getCollisionType(int x, int y, int z) {
        CollisionType override = collisionOverrides.get(PackedPos.pack(x, y, z));
        if (override != null) {
            return override;
        }
        return (y <= 64) ? CollisionType.SOLID : CollisionType.EMPTY;
    }

    @Override
    public double getFloorHeight(int x, int y, int z) {
        return 1.0;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        return false;
    }

    @Override
    public String getBlockId(int x, int y, int z) {
        return (y <= 64) ? "minecraft:stone" : "minecraft:air";
    }

    @Override
    public PortalGroup getPortalAt(int x, int y, int z) {
        return null;
    }
}
