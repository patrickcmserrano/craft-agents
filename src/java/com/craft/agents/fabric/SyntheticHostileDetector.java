package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deteccao de monstros hostis in-JVM para combate tatico e kiting (CA-E1.3).
 * Classe nova carregavel dinamicamente no KnotClassLoader sem violar redefinicao de classes.
 */
public final class SyntheticHostileDetector {
    private SyntheticHostileDetector() {}

    public static List<SyntheticCombat.HostileTarget> scanNearbyHostiles(Object playerEntity, double radius, ClassLoader cl) {
        if (playerEntity == null) return Collections.emptyList();
        try {
            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
            double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
            double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);

            Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
            Object box = boxClass.getConstructor(
                    double.class, double.class, double.class, double.class, double.class, double.class)
                    .newInstance(px - radius, py - 4.0, pz - radius, px + radius, py + 4.0, pz + radius);

            Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
            Class<?> hostileClass = Class.forName("net.minecraft.class_1588", true, cl); // HostileEntity (Monsters)
            Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);

            List<?> ents = (List<?>) world.getClass()
                    .getMethod("method_8335", entClass, boxClass)
                    .invoke(world, null, box);

            List<SyntheticCombat.HostileTarget> list = new ArrayList<>();
            for (Object e : ents) {
                if (e != null && (hostileClass.isInstance(e) || mobClass.isInstance(e))) {
                    String rawId = EntityTargetResolver.getEntityRegistryIdOrName(e, cl);
                    String lower = rawId.toLowerCase(Locale.ROOT);
                    boolean isHostile = hostileClass.isInstance(e)
                            || lower.contains("zombie") || lower.contains("skeleton")
                            || lower.contains("spider") || lower.contains("creeper")
                            || lower.contains("witch") || lower.contains("pillager")
                            || lower.contains("ravager");
                    if (!isHostile) continue;

                    int entityId = 0;
                    try {
                        entityId = (Integer) e.getClass().getMethod("method_5628").invoke(e);
                    } catch (Throwable ignored) {
                        try {
                            entityId = (Integer) e.getClass().getMethod("getId").invoke(e);
                        } catch (Throwable ignored2) {}
                    }

                    float hp = 20.0f;
                    try {
                        hp = ((Number) e.getClass().getMethod("method_6032").invoke(e)).floatValue();
                    } catch (Throwable ignored) {}

                    double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                    double ey = (Double) e.getClass().getMethod("method_23318").invoke(e);
                    double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                    double dist = Math.sqrt((ex - px)*(ex - px) + (ey - py)*(ey - py) + (ez - pz)*(ez - pz));

                    list.add(new SyntheticCombat.HostileTarget(entityId, rawId, ex, ey, ez, dist, hp));
                }
            }
            list.sort(Comparator.comparingDouble(SyntheticCombat.HostileTarget::distance));
            return list;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static String formatHostilesProtocol(List<SyntheticCombat.HostileTarget> hostiles) {
        if (hostiles == null || hostiles.isEmpty()) {
            return "OK|0|";
        }
        StringBuilder sb = new StringBuilder("OK|").append(hostiles.size()).append("|");
        boolean first = true;
        for (SyntheticCombat.HostileTarget h : hostiles) {
            if (!first) sb.append(";");
            sb.append(h.entityId()).append(",")
              .append(h.typeName()).append(",")
              .append(String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.1f", h.x(), h.y(), h.z(), h.distance(), h.hp()));
            first = false;
        }
        return sb.toString();
    }
}
