package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Combate tatico nativo (CA-E1.3 - mineflayer-pvp).
 * Sincronia 20Hz com medidor oficial de ataque da 1.21.1 (getAttackCooldownProgress),
 * deteccao de hostis e disparo de armas JEG.
 */
public final class SyntheticCombat {
    private SyntheticCombat() {}

    public static record HostileTarget(int entityId, String typeName, double x, double y, double z, double distance, float hp) {}

    public static boolean attackMelee(Object playerEntity, Object targetEntity) {
        if (playerEntity == null || targetEntity == null) return false;
        try {
            Method mAttack = null;
            for (Method m : playerEntity.getClass().getMethods()) {
                if (("method_7324".equals(m.getName()) || "attack".equals(m.getName()))
                        && m.getParameterCount() == 1) {
                    mAttack = m;
                    break;
                }
            }
            if (mAttack != null) {
                mAttack.invoke(playerEntity, targetEntity);
                return true;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static void runAttackOnServer(Object server, Object playerEntity, Object targetEntity) {
        if (server == null) {
            attackMelee(playerEntity, targetEntity);
            return;
        }
        try {
            Method mRun = null;
            for (Method m : server.getClass().getMethods()) {
                if (("method_3748".equals(m.getName()) || "submit".equals(m.getName()) || "send".equals(m.getName()))
                        && m.getParameterCount() == 1 && Runnable.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    mRun = m;
                    break;
                }
            }
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    attackMelee(playerEntity, targetEntity);
                }
            };
            if (mRun != null) {
                mRun.invoke(server, task);
            } else {
                attackMelee(playerEntity, targetEntity);
            }
        } catch (Throwable ignored) {
            attackMelee(playerEntity, targetEntity);
        }
    }

    /**
     * Verifica se a barra de ataque do jogador esta carregada (>= 0.9) para dano pleno.
     */
    public static float getAttackCooldown(Object playerEntity) {
        if (playerEntity == null) return 1.0f;
        try {
            Method mCooldown = null;
            for (Method m : playerEntity.getClass().getMethods()) {
                if (("method_7261".equals(m.getName()) || "getAttackCooldownProgress".equals(m.getName()))
                        && m.getParameterCount() == 1) {
                    mCooldown = m;
                    break;
                }
            }
            if (mCooldown != null) {
                return ((Number) mCooldown.invoke(playerEntity, 0.5f)).floatValue();
            }
        } catch (Throwable ignored) {}
        return 1.0f;
    }

    /**
     * Escaneia entidades hostis (HostileEntity / Monster) ao redor do jogador.
     */
    public static List<HostileTarget> scanNearbyHostiles(Object playerEntity, double radius, ClassLoader cl) {
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

            List<HostileTarget> list = new ArrayList<>();
            for (Object e : ents) {
                if (e != null && (hostileClass.isInstance(e) || mobClass.isInstance(e))) {
                    String rawId = EntityTargetResolver.getEntityRegistryIdOrName(e, cl);
                    String lower = rawId.toLowerCase(Locale.ROOT);
                    // Checa se e hostil por classe ou por id comum (zombie, skeleton, spider, creeper, pillager)
                    boolean isHostile = hostileClass.isInstance(e)
                            || lower.contains("zombie") || lower.contains("skeleton")
                            || lower.contains("spider") || lower.contains("creeper")
                            || lower.contains("witch") || lower.contains("pillager")
                            || lower.contains("ravager") || lower.contains("enderman");
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

                    list.add(new HostileTarget(entityId, rawId, ex, ey, ez, dist, hp));
                }
            }
            list.sort(Comparator.comparingDouble(HostileTarget::distance));
            return list;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static String formatHostilesProtocol(List<HostileTarget> hostiles) {
        if (hostiles == null || hostiles.isEmpty()) {
            return "OK|0|";
        }
        StringBuilder sb = new StringBuilder("OK|").append(hostiles.size()).append("|");
        boolean first = true;
        for (HostileTarget h : hostiles) {
            if (!first) sb.append(";");
            sb.append(h.entityId()).append(",")
              .append(h.typeName()).append(",")
              .append(String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.1f", h.x(), h.y(), h.z(), h.distance(), h.hp()));
            first = false;
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static boolean fireRangedItem(Object playerAtom, Object targetAtom, String weaponItemId) {
        if (playerAtom == null || targetAtom == null) return false;
        try {
            Method derefPlayer = playerAtom.getClass().getMethod("deref");
            Object pCur = derefPlayer.invoke(playerAtom);
            if (pCur instanceof Map<?, ?> pMap) {
                Map<Object, Object> pCopy = new HashMap<>(pMap);
                for (Map.Entry<?, ?> e : pMap.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (k.equals(":ammo") || k.equals("ammo")) {
                        Map<Object, Object> ammo = new HashMap<>((Map<?, ?>) e.getValue());
                        for (Map.Entry<?, ?> ae : ammo.entrySet()) {
                            int count = ((Number) ae.getValue()).intValue();
                            if (count > 0) {
                                ammo.put(ae.getKey(), count - 1);
                                break;
                            }
                        }
                        pCopy.put(e.getKey(), ammo);
                    }
                }
                Method resetPlayer = playerAtom.getClass().getMethod("reset", Object.class);
                resetPlayer.invoke(playerAtom, pCopy);
            }

            Method derefTarget = targetAtom.getClass().getMethod("deref");
            Object tCur = derefTarget.invoke(targetAtom);
            if (tCur instanceof Map<?, ?> tMap) {
                Map<Object, Object> tCopy = new HashMap<>(tMap);
                for (Map.Entry<?, ?> e : tMap.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (k.equals(":health") || k.equals("health")) {
                        double health = ((Number) e.getValue()).doubleValue();
                        tCopy.put(e.getKey(), Math.max(0.0, health - 15.0));
                    }
                }
                Method resetTarget = targetAtom.getClass().getMethod("reset", Object.class);
                resetTarget.invoke(targetAtom, tCopy);
            }
            return true;
        } catch (Throwable ignored) {}
        return false;
    }
}
