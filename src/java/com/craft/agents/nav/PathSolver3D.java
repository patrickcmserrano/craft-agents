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
 * Motor A* 3D Zero-Allocation sobre VoxelAccessor com suporte a PortalGroup,
 * metas polimórficas (Goal3D) e restrições cinemáticas do Mineflayer (MovementCapabilities).
 */
public final class PathSolver3D {
    private static final int[][] CARDINAL_DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final int[][] DIAGONAL_DIRS = {
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * Resolve rota 3D guiada por um Goal3D arbitrário e capacidades cinemáticas completas.
     */
    public PathResult solve(
        VoxelAccessor world,
        int startX, int startY, int startZ,
        Goal3D goal,
        MovementCapabilities capabilities,
        int maxNodes
    ) {
        long startTime = System.nanoTime();
        if (capabilities == null) {
            capabilities = MovementCapabilities.defaults();
        }
        if (goal == null) {
            goal = new GoalBlock(startX, startY, startZ);
        }

        // CA-062: partida dentro de sólido -> recupera uma partida válida próxima
        final int originalY = startY;
        PathNodeStep recoveryStep = null;
        if (!isValidStart(world, capabilities, startX, startY, startZ)) {
            long rec = recoverStart(world, capabilities, startX, startY, startZ);
            if (rec == -1L) {
                return new PathResult(PathResult.Status.NO_PATH, List.of(), 0.0, 0,
                    (System.nanoTime() - startTime) / 1000L);
            }
            startX = PackedPos.unpackX(rec);
            startY = PackedPos.unpackY(rec);
            startZ = PackedPos.unpackZ(rec);
            recoveryStep = new PathNodeStep(startX, startY, startZ,
                startY > originalY ? PathNodeStep.StepAction.JUMP_UP : PathNodeStep.StepAction.WALK, null);
        }

        long startPacked = PackedPos.pack(startX, startY, startZ);
        if (goal.isSatisfied(startX, startY, startZ)) {
            List<PathNodeStep> steps = new ArrayList<>();
            if (recoveryStep != null) {
                steps.add(recoveryStep);
            } else {
                steps.add(new PathNodeStep(startX, startY, startZ, PathNodeStep.StepAction.WALK, null));
            }
            return new PathResult(PathResult.Status.SUCCESS, steps, 0.0, 1, 0L);
        }

        PrimitiveMinHeap openSet = new PrimitiveMinHeap(512);
        LongHashSet closedSet = new LongHashSet(1024);

        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScores = new HashMap<>();
        Map<Long, PathNodeStep.StepAction> actions = new HashMap<>();
        Map<Long, PortalGroup> portalMap = new HashMap<>();

        gScores.put(startPacked, 0.0);
        openSet.push(startPacked, goal.heuristic(startX, startY, startZ));

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

            if (goal.isSatisfied(cx, cy, cz)) {
                bestEndPacked = currentPacked;
                reached = true;
                break;
            }

            double currentG = gScores.getOrDefault(currentPacked, Double.MAX_VALUE);

            // 1. Vizinhos Cardinais
            for (int[] d : CARDINAL_DIRS) {
                int nx = cx + d[0];
                int nz = cz + d[1];

                // Passo no mesmo plano horizontal
                evaluateNeighbor(world, cx, cy, cz, nx, cy, nz, currentPacked, currentG,
                                 capabilities.walkCost, PathNodeStep.StepAction.WALK,
                                 goal, capabilities, openSet, closedSet, cameFrom, gScores, actions, portalMap);

                // Step-up / Jump-up
                // Regra Mineflayer: para saltar, é preciso headroom livre de 3 blocos na origem (cy, cy+1, cy+2 livres)
                if (world.getCollisionType(cx, cy + 2, cz) != CollisionType.SOLID) {
                    evaluateNeighbor(world, cx, cy, cz, nx, cy + 1, nz, currentPacked, currentG,
                                     capabilities.jumpUpCost, PathNodeStep.StepAction.JUMP_UP,
                                     goal, capabilities, openSet, closedSet, cameFrom, gScores, actions, portalMap);
                }

                // Drop-down (descida de desnível segura)
                for (int drop = 1; drop <= capabilities.maxDropDown; drop++) {
                    evaluateNeighbor(world, cx, cy, cz, nx, cy - drop, nz, currentPacked, currentG,
                                     capabilities.walkCost + (drop * 0.5), PathNodeStep.StepAction.DROP_DOWN,
                                     goal, capabilities, openSet, closedSet, cameFrom, gScores, actions, portalMap);
                }
            }

            // 2. Vizinhos Diagonais (com verificação rigorosa de quinas / corner-cutting)
            for (int[] d : DIAGONAL_DIRS) {
                int nx = cx + d[0];
                int nz = cz + d[1];

                // Verificação de quina: não pode cortar diagonal se houver obstáculo sólido ou portal nos lados
                CollisionType s1Foot = world.getCollisionType(cx + d[0], cy, cz);
                CollisionType s1Head = world.getCollisionType(cx + d[0], cy + 1, cz);
                CollisionType s2Foot = world.getCollisionType(cx, cy, cz + d[1]);
                CollisionType s2Head = world.getCollisionType(cx, cy + 1, cz + d[1]);

                if (s1Foot == CollisionType.SOLID || s1Head == CollisionType.SOLID ||
                    s2Foot == CollisionType.SOLID || s2Head == CollisionType.SOLID ||
                    s1Foot == CollisionType.PORTAL || s2Foot == CollisionType.PORTAL) {
                    continue;
                }

                // Em condução na guia (escortLeashed), exige margem de manobra adicional para evitar enrosco
                if (capabilities.escortLeashed) {
                    if (!capabilities.canStandOn(world, cx + d[0], cy - 1, cz) ||
                        !capabilities.canStandOn(world, cx, cy - 1, cz + d[1])) {
                        continue;
                    }
                }

                evaluateNeighbor(world, cx, cy, cz, nx, cy, nz, currentPacked, currentG,
                                 capabilities.diagonalCost, PathNodeStep.StepAction.DIAGONAL,
                                 goal, capabilities, openSet, closedSet, cameFrom, gScores, actions, portalMap);
            }
        }

        long elapsedMicros = (System.nanoTime() - startTime) / 1000L;

        if (!reached || bestEndPacked == -1L) {
            return new PathResult(PathResult.Status.NO_PATH, List.of(), 0.0, visitedNodes, elapsedMicros);
        }

        // Reconstrói rota percorrida
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
        if (recoveryStep != null) {
            steps.add(0, recoveryStep);
        }

        double totalCost = gScores.getOrDefault(bestEndPacked, 0.0);
        return new PathResult(PathResult.Status.SUCCESS, steps, totalCost, visitedNodes, elapsedMicros);
    }

    private static boolean isValidStart(VoxelAccessor world, MovementCapabilities caps, int x, int y, int z) {
        CollisionType feet = world.getCollisionType(x, y, z);
        if (feet == CollisionType.SOLID || world.getCollisionType(x, y + 1, z) == CollisionType.SOLID) {
            return false;
        }
        return feet == CollisionType.LIQUID || caps.canStandOn(world, x, y - 1, z);
    }

    /**
     * Procura partida válida a partir de uma posição possivelmente dentro de sólido (CA-062).
     * Ordem: própria posição; mesma coluna y+1..y+3; 8 colunas vizinhas (cardinais, depois
     * diagonais) com dy em 0,+1,-1,+2,+3. Devolve posição empacotada ou -1L.
     */
    public static long recoverStart(VoxelAccessor world, MovementCapabilities caps, int x, int y, int z) {
        if (caps == null) {
            caps = MovementCapabilities.defaults();
        }
        if (isValidStart(world, caps, x, y, z)) {
            return PackedPos.pack(x, y, z);
        }
        for (int dy = 1; dy <= 3; dy++) {
            if (isValidStart(world, caps, x, y + dy, z)) {
                return PackedPos.pack(x, y + dy, z);
            }
        }
        final int[] dys = {0, 1, -1, 2, 3};
        for (int[][] group : new int[][][] {CARDINAL_DIRS, DIAGONAL_DIRS}) {
            for (int[] d : group) {
                for (int dy : dys) {
                    if (isValidStart(world, caps, x + d[0], y + dy, z + d[1])) {
                        return PackedPos.pack(x + d[0], y + dy, z + d[1]);
                    }
                }
            }
        }
        return -1L;
    }

    /**
     * Sobrecarga de compatibilidade retroativa para rotas com raio de aceitação euclidiano.
     */
    public PathResult solveToRadius(
        VoxelAccessor world,
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        double acceptRadius,
        MovementCosts costs,
        int maxNodes
    ) {
        MovementCapabilities caps = MovementCapabilities.fromCosts(costs);
        Goal3D goal = new GoalNear(goalX, goalY, goalZ, acceptRadius);
        return solve(world, startX, startY, startZ, goal, caps, maxNodes);
    }

    private void evaluateNeighbor(
        VoxelAccessor world,
        int cx, int cy, int cz,
        int nx, int ny, int nz,
        long currentPacked,
        double currentG,
        double moveCost,
        PathNodeStep.StepAction action,
        Goal3D goal,
        MovementCapabilities capabilities,
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

        // 1. Verifica perigo (lava, fogo, cacto, etc.) no destino ou sob os pés
        if (capabilities.isHazard(world, nx, ny, nz) || capabilities.isHazard(world, nx, ny - 1, nz)) {
            return;
        }

        CollisionType footCol = world.getCollisionType(nx, ny, nz);
        CollisionType belowCol = world.getCollisionType(nx, ny - 1, nz);
        boolean inWater = (footCol == CollisionType.LIQUID) || (belowCol == CollisionType.LIQUID && footCol == CollisionType.EMPTY);

        // 2. Suporte sólido ou flutuabilidade aquática (SWIM nativo sem afundar no leito)
        if (inWater) {
            action = PathNodeStep.StepAction.SWIM;
            moveCost = capabilities.swimCost;
        } else if (!capabilities.canStandOn(world, nx, ny - 1, nz)) {
            return;
        }

        // 3. Verifica espaço livre para o corpo e a cabeça
        CollisionType headCol = world.getCollisionType(nx, ny + 1, nz);

        PortalGroup portal = null;
        if (capabilities.allowPortals) {
            portal = PortalDetector.detectAt(world, nx, ny, nz);
        }

        if (portal != null && portal.canBotToggle()) {
            action = PathNodeStep.StepAction.TRAVERSE_PORTAL;
            moveCost = capabilities.portalTraverseCost;
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
            double fScore = tentativeG + goal.heuristic(nx, ny, nz);
            openSet.updateOrPush(nPacked, fScore);
        }
    }
}
