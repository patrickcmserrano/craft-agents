package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Varredura e coleta ativa de drops no chao (CA-E1.4 - mineflayer-collectblock).
 * Zero Packet Lag: inspeciona ItemEntity direto no ServerWorld da Mojang.
 */
public final class SyntheticItemCollector {
    private SyntheticItemCollector() {}

    public static record DroppedItem(int entityId, String itemId, int count, double x, double y, double z, double distance) {}

    /**
     * Escaneia ItemEntity caidos no chao num raio ao redor do jogador.
     */
    public static List<DroppedItem> scanNearbyDrops(Object playerEntity, double radius, String itemFilter, ClassLoader cl) {
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
            Class<?> itemEntClass = Class.forName("net.minecraft.class_1542", true, cl); // ItemEntity

            List<?> ents = (List<?>) world.getClass()
                    .getMethod("method_8335", entClass, boxClass)
                    .invoke(world, null, box);

            List<DroppedItem> results = new ArrayList<>();
            String filter = (itemFilter == null || itemFilter.isEmpty() || "all".equalsIgnoreCase(itemFilter)) ? "" : itemFilter.toLowerCase(Locale.ROOT);

            for (Object e : ents) {
                if (itemEntClass.isInstance(e)) {
                    int entityId = 0;
                    try {
                        entityId = (Integer) e.getClass().getMethod("method_5628").invoke(e); // getId
                    } catch (Throwable ignored) {
                        try {
                            entityId = (Integer) e.getClass().getMethod("getId").invoke(e);
                        } catch (Throwable ignored2) {}
                    }

                    Object itemStack = null;
                    try {
                        itemStack = e.getClass().getMethod("method_6983").invoke(e); // getStack
                    } catch (Throwable ignored) {
                        try {
                            itemStack = e.getClass().getMethod("getStack").invoke(e);
                        } catch (Throwable ignored2) {}
                    }

                    String itemId = "minecraft:air";
                    int count = 1;
                    if (itemStack != null) {
                        try {
                            Method mCount = itemStack.getClass().getMethod("method_7947"); // getCount
                            count = ((Number) mCount.invoke(itemStack)).intValue();
                        } catch (Throwable ignored) {
                            try {
                                Method mCount = itemStack.getClass().getMethod("getCount");
                                count = ((Number) mCount.invoke(itemStack)).intValue();
                            } catch (Throwable ignored2) {}
                        }

                        try {
                            Object itemObj = itemStack.getClass().getMethod("method_7909").invoke(itemStack); // getItem
                            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
                            Object itemReg = regClass.getField("field_41178").get(null); // ITEM registry
                            Object idObj = itemReg.getClass().getMethod("method_10221", Object.class).invoke(itemReg, itemObj);
                            if (idObj != null) itemId = idObj.toString();
                        } catch (Throwable ignored) {}
                    }

                    if (!filter.isEmpty() && !itemId.toLowerCase(Locale.ROOT).contains(filter)) {
                        continue;
                    }

                    double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                    double ey = (Double) e.getClass().getMethod("method_23318").invoke(e);
                    double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                    double dist = Math.sqrt((ex - px)*(ex - px) + (ey - py)*(ey - py) + (ez - pz)*(ez - pz));

                    results.add(new DroppedItem(entityId, itemId, count, ex, ey, ez, dist));
                }
            }

            results.sort(Comparator.comparingDouble(DroppedItem::distance));
            return results;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * Formata os drops encontrados para o protocolo de resposta da ponte.
     */
    public static String formatDropsProtocol(List<DroppedItem> drops) {
        if (drops == null || drops.isEmpty()) {
            return "OK|0|";
        }
        StringBuilder sb = new StringBuilder("OK|").append(drops.size()).append("|");
        boolean first = true;
        for (DroppedItem d : drops) {
            if (!first) sb.append(";");
            sb.append(d.entityId()).append(",")
              .append(d.itemId()).append(",")
              .append(d.count()).append(",")
              .append(String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", d.x(), d.y(), d.z(), d.distance()));
            first = false;
        }
        return sb.toString();
    }
}
