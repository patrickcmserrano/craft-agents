package com.craft.agents.nav;

import com.craft.agents.api.CollisionType;
import com.craft.agents.api.PathNodeStep;
import com.craft.agents.api.PathResult;
import com.craft.agents.api.PortalGroup;
import com.craft.agents.api.VoxelAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor A* 3D Zero-Allocation sobre VoxelAccessor com suporte nativo a PortalGroup (CA-024).
 */
public final class PathSolver3D {
    private static final int[][] CARDINAL_DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final int[][] DIAGONAL_DIRS = {
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    public PathResult solveToRadius(
        VoxelAccessor world,
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        double acceptRadius,
        MovementCosts costs,
        int maxNodes
    ) {
        long startTime = System.nanoTime();
        if (costs == null) {
            costs = MovementCosts.defaults();
        }

        long startPacked = PackedPos.pack(startX, startY, startZ);
        double distStart = dist(startX, startY, startZ, goalX, goalY, goalZ);
        if (distStart <= acceptRadius) {
            List<PathNodeStep> steps = new ArrayList<>();
            steps.add(new PathNodeStep(startX, startY, startZ, PathNodeStep.StepAction.WALK, null));
            return new PathResult(PathResult.Status.SUCCESS, steps, 0.0, 1, 0L);
        }

        PrimitiveMinHeap openSet = new PrimitiveMinHeap(512);
        LongHashSet closedSet = new LongHashSet(1024);

        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScores = new HashMap<>();
        Map<Long, PathNodeStep.StepAction> actions = new HashMap<>();
        Map<Long, PortalGroup> portalMap = new HashMap<>();

        gScores.put(startPacked, 0.0);
        openSet.push(startPacked, distStart);

        int visitedNodes = 0;
        long bestEndPacked = -1L;
        boolean reached = false;

        while (!openSet.isEmpty() && visitedNodes < maxNodes) {
            long currentPacked = openSet.popMin();
            if (closedSet.contains(currentPacked)) {
                continue;
            }
            closedSet.add(currentPacked);
            visitedNodes++;

            int cx = PackedPos.unpackX(currentPacked);
            int cy = PackedPos.unpackY(currentPacked);
            int cz = PackedPos.unpackZ(currentPacked);

            if (dist(cx, cy, cz, goalX, goalY, goalZ) <= acceptRadius) {
                bestEndPacked = currentPacked;
                reached = true;
                break;
            }

            double currentG = gScores.getOrDefault(currentPacked, Double.MAX_VALUE);

            // 1. Vizinhos Cardinais
            for (int[] d : CARDINAL_DIRS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                evaluateNeighbor(world, cx, cy, cz, nx, cy, nz, currentPacked, currentG,
                                 costs.walkCost(), PathNodeStep.StepAction.WALK,
                                 goalX, goalY, goalZ, costs, openSet, closedSet, cameFrom, gScores, actions, portalMap);

                // Step-up / Jump-up
                evaluateNeighbor(world, cx, cy, cz, nx, cy + 1, nz, currentPacked, currentG,
                                 costs.jumpUpCost(), PathNodeStep.StepAction.JUMP_UP,
                                 goalX, goalY, goalZ, costs, openSet, closedSet, cameFrom, gScores, actions, portalMap);

                // Drop-down
                for (int drop = 1; drop <= costs.maxDropDown(); drop++) {
                    evaluateNeighbor(world, cx, cy, cz, nx, cy - drop, nz, currentPacked, currentG,
                                     costs.walkCost() + (drop * 0.5), PathNodeStep.StepAction.DROP_DOWN,
                                     goalX, goalY, goalZ, costs, openSet, closedSet, cameFrom, gScores, actions, portalMap);
                }
            }

            // 2. Vizinhos Diagonais
            for (int[] d : DIAGONAL_DIRS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                evaluateNeighbor(world, cx, cy, cz, nx, cy, nz, currentPacked, currentG,
                                 costs.diagonalCost(), PathNodeStep.StepAction.DIAGONAL,
                                 goalX, goalY, goalZ, costs, openSet, closedSet, cameFrom, gScores, actions, portalMap);
            }
        }

        long elapsedMicros = (System.nanoTime() - startTime) / 1000L;

        if (!reached || bestEndPacked == -1L) {
            return new PathResult(PathResult.Status.NO_PATH, List.of(), 0.0, visitedNodes, elapsedMicros);
        }

        // Reconstrói rota
        List<PathNodeStep> steps = new ArrayList<>();
        long curr = bestEndPacked;
        while (curr != startPacked && cameFrom.containsKey(curr)) {
            int x = PackedPos.unpackX(curr);
            int y = PackedPos.unpackY(curr);
            int z = PackedPos.unpackZ(curr);
            PathNodeStep.StepAction act = actions.getOrDefault(curr, PathNodeStep.StepAction.WALK);
            PortalGroup pGroup = portalMap.get(curr);
            steps.add(new PathNodeStep(x, y, z, act, pGroup));
            curr = cameFrom.get(curr);
        }
        Collections.reverse(steps);

        double totalCost = gScores.getOrDefault(bestEndPacked, 0.0);
        return new PathResult(PathResult.Status.SUCCESS, steps, totalCost, visitedNodes, elapsedMicros);
    }

    private void evaluateNeighbor(
        VoxelAccessor world,
        int cx, int cy, int cz,
        int nx, int ny, int nz,
        long currentPacked,
        double currentG,
        double moveCost,
        PathNodeStep.StepAction action,
        int goalX, int goalY, int goalZ,
        MovementCosts costs,
        PrimitiveMinHeap openSet,
        LongHashSet closedSet,
        Map<Long, Long> cameFrom,
        Map<Long, Double> gScores,
        Map<Long, PathNodeStep.StepAction> actions,
        Map<Long, PortalGroup> portalMap
    ) {
        long nPacked = PackedPos.pack(nx, ny, nz);
        if (closedSet.contains(nPacked)) {
            return;
        }

        // Verifica perigo (lava, fogo) no destino ou sob os pes
        if (world.isHazard(nx, ny, nz) || world.isHazard(nx, ny - 1, nz)) {
            return;
        }

        // Suporte solido ou transitavel sob os pes
        CollisionType floorCollision = world.getCollisionType(nx, ny - 1, nz);
        if (floorCollision == CollisionType.SOLID || floorCollision == CollisionType.PORTAL) {
            // OK
        } else {
            return;
        }

        // Verifica espaco do corpo e da cabeca
        CollisionType footCol = world.getCollisionType(nx, ny, nz);
        CollisionType headCol = world.getCollisionType(nx, ny + 1, nz);

        PortalGroup portal = null;
        if (costs.allowPortals()) {
            portal = PortalDetector.detectAt(world, nx, ny, nz);
        }

        if (portal != null && portal.canBotToggle()) {
            action = PathNodeStep.StepAction.TRAVERSE_PORTAL;
            moveCost = costs.portalTraverseCost();
        } else {
            if (footCol == CollisionType.SOLID || headCol == CollisionType.SOLID) {
                return;
            }
        }

        double tentativeG = currentG + moveCost;
        Double existingG = gScores.get(nPacked);
        if (existingG == null || tentativeG < existingG) {
            gScores.put(nPacked, tentativeG);
            cameFrom.put(nPacked, currentPacked);
            actions.put(nPacked, action);
            if (portal != null) {
                portalMap.put(nPacked, portal);
            }
            double fScore = tentativeG + dist(nx, ny, nz, goalX, goalY, goalZ);
            openSet.updateOrPush(nPacked, fScore);
        }
    }

    private static double dist(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
