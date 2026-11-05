package com.craft.agents.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Varredor de minerios realistas com deteccao estrita de faces expostas (Anti X-Ray).
 * Filtra blocos completamente soterrados que o jogador nao consegue ver,
 * impedindo que o bot tente quebrar paredes solidas para alcancar veios internos.
 */
public final class SyntheticExposedOreScanner {
    private SyntheticExposedOreScanner() {}

    public static record ExposedOreTarget(
            int x, int y, int z,
            String blockId,
            double distance,
            int airX, int airY, int airZ) {}

    private static final int[][] ADJACENT_FACES = {
        {0, 1, 0},   // teto/topo
        {0, -1, 0},  // piso
        {1, 0, 0},   // leste
        {-1, 0, 0},  // oeste
        {0, 0, 1},   // sul
        {0, 0, -1}   // norte
    };

    public static List<ExposedOreTarget> scanExposedOres(Object playerEntity, double radius, String filter, ClassLoader cl) {
        if (playerEntity == null) return Collections.emptyList();
        try {
            double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
            double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
            double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);

            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Class<?> bpClass = Class.forName("net.minecraft.class_2338", true, cl);
            Constructor<?> bpCtor = bpClass.getConstructor(int.class, int.class, int.class);
            Method getBlockStateMethod = world.getClass().getMethod("method_8320", bpClass);

            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object blockReg = regClass.getField("field_41175").get(null);
            Method getIdMethod = blockReg.getClass().getMethod("method_10221", Object.class);

            int r = (int) Math.min(Math.max(radius, 2.0), 16.0);
            int minX = (int) Math.floor(px) - r;
            int maxX = (int) Math.floor(px) + r;
            int minY = Math.max(-64, (int) Math.floor(py) - r);
            int maxY = Math.min(320, (int) Math.floor(py) + r);
            int minZ = (int) Math.floor(pz) - r;
            int maxZ = (int) Math.floor(pz) + r;

            String oreFilter = filter != null ? filter.trim().toLowerCase(Locale.ROOT) : "";
            List<ExposedOreTarget> ores = new ArrayList<>();

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Object pos = bpCtor.newInstance(x, y, z);
                        Object state = getBlockStateMethod.invoke(world, pos);
                        boolean isAir = (Boolean) state.getClass().getMethod("method_26215").invoke(state);
                        if (isAir) continue;

                        Object block = state.getClass().getMethod("method_26204").invoke(state);
                        Object idObj = getIdMethod.invoke(blockReg, block);
                        if (idObj == null) continue;
                        String id = idObj.toString();

                        boolean matches = false;
                        if (!oreFilter.isEmpty()) {
                            matches = id.contains(oreFilter);
                        } else {
                            matches = id.contains("_ore")
                                    || id.contains("ancient_debris")
                                    || id.contains("raw_iron_block")
                                    || id.contains("raw_gold_block")
                                    || id.contains("raw_copper_block");
                        }

                        if (!matches) continue;

                        // Anti X-Ray: busca vizinho de ar adjacente
                        int bestAirX = Integer.MIN_VALUE, bestAirY = Integer.MIN_VALUE, bestAirZ = Integer.MIN_VALUE;
                        double bestAirDist = Double.MAX_VALUE;

                        for (int[] face : ADJACENT_FACES) {
                            int nx = x + face[0];
                            int ny = y + face[1];
                            int nz = z + face[2];
                            Object nPos = bpCtor.newInstance(nx, ny, nz);
                            Object nState = getBlockStateMethod.invoke(world, nPos);
                            boolean nAir = (Boolean) nState.getClass().getMethod("method_26215").invoke(nState);
                            if (nAir) {
                                double adx = (nx + 0.5) - px;
                                double ady = (ny + 0.5) - py;
                                double adz = (nz + 0.5) - pz;
                                double aDist = adx * adx + ady * ady + adz * adz;
                                if (aDist < bestAirDist) {
                                    bestAirDist = aDist;
                                    bestAirX = nx;
                                    bestAirY = ny;
                                    bestAirZ = nz;
                                }
                            }
                        }

                        // Se nenhuma face tem ar, o minerio esta soterrado e invisivel
                        if (bestAirX == Integer.MIN_VALUE) {
                            continue;
                        }

                        double dx = (x + 0.5) - px;
                        double dy = (y + 0.5) - py;
                        double dz = (z + 0.5) - pz;
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        ores.add(new ExposedOreTarget(x, y, z, id, dist, bestAirX, bestAirY, bestAirZ));
                    }
                }
            }

            Collections.sort(ores, new Comparator<ExposedOreTarget>() {
                @Override
                public int compare(ExposedOreTarget a, ExposedOreTarget b) {
                    return Double.compare(a.distance(), b.distance());
                }
            });

            if (ores.size() > 25) {
                return ores.subList(0, 25);
            }
            return ores;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static String formatOresProtocol(List<ExposedOreTarget> ores) {
        if (ores == null || ores.isEmpty()) {
            return "OK|0|";
        }
        StringBuilder sb = new StringBuilder("OK|").append(ores.size()).append("|");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(";");
            ExposedOreTarget o = ores.get(i);
            sb.append(o.x()).append(",")
              .append(o.y()).append(",")
              .append(o.z()).append(",")
              .append(o.blockId()).append(",")
              .append(String.format(Locale.ROOT, "%.2f", o.distance())).append(",")
              .append(o.airX()).append(",")
              .append(o.airY()).append(",")
              .append(o.airZ());
        }
        return sb.toString();
    }
}
