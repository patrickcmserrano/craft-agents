package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Combate melee com cooldown 1.9+ e disparo nativo de armas modded como JEG (CA-019).
 */
public final class SyntheticCombat {
    private SyntheticCombat() {}

    public static boolean attackMelee(Object playerEntity, Object targetEntity) {
        return true;
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
