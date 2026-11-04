package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Fisiologia nativa e consumo de alimentos in-JVM (CA-E1.1 - mineflayer-auto-eat).
 * Permite leitura de HP, fome (foodLevel) e saturacao, alem de auto-consumo sem lag de rede.
 */
public final class SyntheticPhysiology {
    private SyntheticPhysiology() {}

    public static record HealthStatus(float hp, int foodLevel, float saturation, boolean isAlive) {
        public String toProtocolString() {
            return String.format(java.util.Locale.ROOT, "OK|%.2f|%d|%.2f|%b", hp, foodLevel, saturation, isAlive);
        }
    }

    /**
     * Consulta estado fisiologico do jogador nativo no ServerWorld.
     */
    public static HealthStatus inspectPlayerHealth(Object playerEntity, ClassLoader cl) {
        if (playerEntity == null) {
            return new HealthStatus(0.0f, 0, 0.0f, false);
        }
        try {
            float hp = 20.0f;
            try {
                Method m = playerEntity.getClass().getMethod("method_6032"); // getHealth
                hp = ((Number) m.invoke(playerEntity)).floatValue();
            } catch (Throwable ignored) {
                try {
                    Method m = playerEntity.getClass().getMethod("getHealth");
                    hp = ((Number) m.invoke(playerEntity)).floatValue();
                } catch (Throwable ignored2) {}
            }

            boolean isAlive = true;
            try {
                Method m = playerEntity.getClass().getMethod("method_5805"); // isAlive
                isAlive = (Boolean) m.invoke(playerEntity);
            } catch (Throwable ignored) {
                try {
                    Method m = playerEntity.getClass().getMethod("isAlive");
                    isAlive = (Boolean) m.invoke(playerEntity);
                } catch (Throwable ignored2) {}
            }

            int foodLevel = 20;
            float saturation = 5.0f;
            try {
                Method getHungerMgr = null;
                for (Method m : playerEntity.getClass().getMethods()) {
                    if ("method_7344".equals(m.getName()) || "getHungerManager".equals(m.getName())) {
                        getHungerMgr = m;
                        break;
                    }
                }
                if (getHungerMgr != null) {
                    Object hungerMgr = getHungerMgr.invoke(playerEntity);
                    if (hungerMgr != null) {
                        try {
                            Method mFood = hungerMgr.getClass().getMethod("method_7586"); // getFoodLevel
                            foodLevel = ((Number) mFood.invoke(hungerMgr)).intValue();
                        } catch (Throwable ignored) {
                            try {
                                Method mFood = hungerMgr.getClass().getMethod("getFoodLevel");
                                foodLevel = ((Number) mFood.invoke(hungerMgr)).intValue();
                            } catch (Throwable ignored2) {}
                        }

                        try {
                            Method mSat = hungerMgr.getClass().getMethod("method_7589"); // getSaturationLevel
                            saturation = ((Number) mSat.invoke(hungerMgr)).floatValue();
                        } catch (Throwable ignored) {
                            try {
                                Method mSat = hungerMgr.getClass().getMethod("getSaturationLevel");
                                saturation = ((Number) mSat.invoke(hungerMgr)).floatValue();
                            } catch (Throwable ignored2) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}

            return new HealthStatus(hp, foodLevel, saturation, isAlive);
        } catch (Throwable t) {
            return new HealthStatus(0.0f, 0, 0.0f, false);
        }
    }

    /**
     * Consumo simulado/mockavel para testes puros em Clojure sem dependencia de Fabric live.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> simulateConsumeFood(Map<String, Object> playerState, String foodItemId) {
        if (playerState == null) return new HashMap<>();
        Map<String, Object> updated = new HashMap<>(playerState);
        Map<String, Object> inv = new HashMap<>((Map<String, Object>) updated.getOrDefault("inventory", Map.of()));
        
        int count = ((Number) inv.getOrDefault(foodItemId, 0)).intValue();
        if (count <= 0) {
            return updated; // sem comida disponivel
        }
        if (count == 1) {
            inv.remove(foodItemId);
        } else {
            inv.put(foodItemId, count - 1);
        }
        updated.put("inventory", inv);

        int currentFood = ((Number) updated.getOrDefault("food", 20)).intValue();
        double currentHp = ((Number) updated.getOrDefault("health", 20.0)).doubleValue();

        int restoredFood = 4;
        if (foodItemId.contains("beef") || foodItemId.contains("porkchop")) restoredFood = 8;
        else if (foodItemId.contains("bread")) restoredFood = 5;
        else if (foodItemId.contains("carrot")) restoredFood = 3;

        updated.put("food", Math.min(20, currentFood + restoredFood));
        updated.put("health", Math.min(20.0, currentHp + 2.0));
        return updated;
    }
}
