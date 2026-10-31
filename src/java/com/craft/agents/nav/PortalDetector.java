package com.craft.agents.nav;

import com.craft.agents.api.CollisionType;
import com.craft.agents.api.PortalGroup;
import com.craft.agents.api.VoxelAccessor;

/**
 * Detector geometrico de portas, portas duplas e portoes empilhados y / y+1 (CA-023).
 */
public final class PortalDetector {
    private PortalDetector() {}

    public static PortalGroup detectAt(VoxelAccessor world, int x, int y, int z) {
        PortalGroup existing = world.getPortalAt(x, y, z);
        if (existing != null) {
            return existing;
        }

        String blockId = world.getBlockId(x, y, z);
        if (blockId == null) {
            return null;
        }

        boolean isGate = blockId.contains("fence_gate") || blockId.contains("_gate");
        boolean isDoor = blockId.contains("_door");

        if (!isGate && !isDoor) {
            return null;
        }

        boolean isIron = blockId.contains("iron_door");
        boolean canToggle = !isIron;

        String aboveId = world.getBlockId(x, y + 1, z);
        boolean isStacked = (aboveId != null) && (aboveId.contains("gate") || aboveId.contains("door"));

        long[] positions;
        if (isStacked) {
            positions = new long[]{PackedPos.pack(x, y, z), PackedPos.pack(x, y + 1, z)};
        } else {
            positions = new long[]{PackedPos.pack(x, y, z)};
        }

        boolean currentlyOpen = (world.getCollisionType(x, y, z) == CollisionType.EMPTY);

        return new PortalGroup(
            "portal-" + x + "-" + y + "-" + z,
            positions,
            x + 0.5,
            y,
            z + 0.5,
            1.0,
            0.0,
            canToggle,
            currentlyOpen
        );
    }
}
