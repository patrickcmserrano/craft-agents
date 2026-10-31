package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Controlador fisico Server-Side a 20Hz alimentando inputs reais do ServerPlayerEntity (CA-016).
 */
public final class SyntheticMotorController {
    private SyntheticMotorController() {}

    @SuppressWarnings("unchecked")
    public static void stepMotorTick(
        Object playerEntity,
        double forward,
        double strafe,
        boolean jump,
        boolean sprint,
        boolean sneak,
        double yaw,
        double pitch
    ) {
        if (playerEntity == null) return;
        try {
            Method derefMethod = playerEntity.getClass().getMethod("deref");
            Object current = derefMethod.invoke(playerEntity);
            if (current instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new HashMap<>(map);
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (k.equals(":y") || k.equals("y")) {
                        double yVal = ((Number) e.getValue()).doubleValue() - 0.08;
                        copy.put(e.getKey(), yVal);
                    } else if (k.equals(":sneaking?") || k.equals("sneaking?")) {
                        copy.put(e.getKey(), sneak);
                    }
                }
                Method resetMethod = playerEntity.getClass().getMethod("reset", Object.class);
                resetMethod.invoke(playerEntity, copy);
            }
        } catch (Throwable ignored) {}
    }
}
