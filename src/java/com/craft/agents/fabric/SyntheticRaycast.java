package com.craft.agents.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Calculador de Raycast e Linha de Visada (Joint Attention / Joint Gaze) in-JVM (CA-E3.1).
 * Detecta o bloco exato para o qual o jogador esta olhando atraves de amostragem
 * rapida ao longo do vetor de visao (DDA / step-sampling continuo < 0.05ms).
 */
public final class SyntheticRaycast {
    private SyntheticRaycast() {}

    public static SyntheticRaycastResult raycastBlock(Object playerEntity, double maxDist, ClassLoader cl) {
        if (playerEntity == null) {
            return new SyntheticRaycastResult(false, "", 0, 0, 0, 0.0);
        }
        try {
            double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
            double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
            double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);
            double eyeY = py + 1.62;
            try {
                eyeY = (Double) playerEntity.getClass().getMethod("method_23320").invoke(playerEntity);
            } catch (Throwable ignored) {}

            double dirX = 0, dirY = 0, dirZ = 1;
            boolean gotVec = false;
            try {
                Object rotVec = playerEntity.getClass().getMethod("method_5720", float.class).invoke(playerEntity, 1.0f);
                if (rotVec != null) {
                    dirX = rotVec.getClass().getField("field_1352").getDouble(rotVec);
                    dirY = rotVec.getClass().getField("field_1351").getDouble(rotVec);
                    dirZ = rotVec.getClass().getField("field_1350").getDouble(rotVec);
                    gotVec = true;
                }
            } catch (Throwable ignored) {}

            if (!gotVec) {
                float yaw = 0f;
                float pitch = 0f;
                try {
                    yaw = ((Number) playerEntity.getClass().getMethod("method_36454").invoke(playerEntity)).floatValue();
                    pitch = ((Number) playerEntity.getClass().getMethod("method_36455").invoke(playerEntity)).floatValue();
                } catch (Throwable ignored) {}

                double yawRad = Math.toRadians(yaw);
                double pitchRad = Math.toRadians(pitch);
                dirX = -Math.sin(yawRad) * Math.cos(pitchRad);
                dirY = -Math.sin(pitchRad);
                dirZ = Math.cos(yawRad) * Math.cos(pitchRad);
            }

            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Class<?> bpClass = Class.forName("net.minecraft.class_2338", true, cl);
            Constructor<?> bpCtor = bpClass.getConstructor(int.class, int.class, int.class);
            Method getBlockStateMethod = world.getClass().getMethod("method_8320", bpClass);

            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object blockReg = regClass.getField("field_41175").get(null);
            Method getIdMethod = blockReg.getClass().getMethod("method_10221", Object.class);

            double step = 0.15;
            int maxSteps = (int) (maxDist / step);
            int lastBx = Integer.MIN_VALUE, lastBy = Integer.MIN_VALUE, lastBz = Integer.MIN_VALUE;

            for (int i = 1; i <= maxSteps; i++) {
                double cx = px + dirX * (i * step);
                double cy = eyeY + dirY * (i * step);
                double cz = pz + dirZ * (i * step);

                int bx = (int) Math.floor(cx);
                int by = (int) Math.floor(cy);
                int bz = (int) Math.floor(cz);

                if (bx == lastBx && by == lastBy && bz == lastBz) continue;
                lastBx = bx;
                lastBy = by;
                lastBz = bz;

                Object pos = bpCtor.newInstance(bx, by, bz);
                Object state = getBlockStateMethod.invoke(world, pos);
                boolean isAir = (Boolean) state.getClass().getMethod("method_26215").invoke(state);
                if (!isAir) {
                    Object block = state.getClass().getMethod("method_26204").invoke(state);
                    Object idObj = getIdMethod.invoke(blockReg, block);
                    String id = idObj != null ? idObj.toString() : "minecraft:stone";
                    double dist = Math.sqrt((cx - px) * (cx - px) + (cy - eyeY) * (cy - eyeY) + (cz - pz) * (cz - pz));
                    return new SyntheticRaycastResult(true, id, bx, by, bz, dist);
                }
            }
            return new SyntheticRaycastResult(false, "", 0, 0, 0, 0.0);
        } catch (Throwable t) {
            return new SyntheticRaycastResult(false, "", 0, 0, 0, 0.0);
        }
    }
}
