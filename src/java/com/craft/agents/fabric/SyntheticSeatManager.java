package com.craft.agents.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gerenciador nativo de assentos, sofás e mobílias para jogadores sintéticos (CA-E1.5).
 */
public final class SyntheticSeatManager {
    private SyntheticSeatManager() {}

    public static String sit(Object server, Object playerEntity, int sx, int sy, int sz, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Class<?> bpCls = Class.forName("net.minecraft.class_2338", true, cl);
            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object blockReg = regClass.getField("field_41175").get(null);
            Method getIdMethod = blockReg.getClass().getMethod("method_10221", Object.class);

            int targetX = sx;
            int targetY = sy;
            int targetZ = sz;

            if (targetX == Integer.MIN_VALUE) {
                double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
                double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
                double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);

                int ipx = (int) Math.floor(px);
                int ipy = (int) Math.floor(py);
                int ipz = (int) Math.floor(pz);

                double bestDistSq = Double.MAX_VALUE;
                int bestX = Integer.MIN_VALUE;
                int bestY = Integer.MIN_VALUE;
                int bestZ = Integer.MIN_VALUE;

                Method isOccupiedMethod = null;
                try {
                    Class<?> seatBlockCls = Class.forName("com.starfish_studios.another_furniture.block.SeatBlock", true, cl);
                    Class<?> worldCls = Class.forName("net.minecraft.class_1937", true, cl);
                    isOccupiedMethod = seatBlockCls.getMethod("isSeatOccupied", worldCls, bpCls);
                } catch (Throwable ignored) {}

                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        for (int dy = -1; dy <= 2; dy++) {
                            int cx = ipx + dx;
                            int cy = ipy + dy;
                            int cz = ipz + dz;

                            Object bp = bpCls.getConstructor(int.class, int.class, int.class).newInstance(cx, cy, cz);
                            Object bState = world.getClass().getMethod("method_8320", bpCls).invoke(world, bp);
                            Object block = bState.getClass().getMethod("method_26204").invoke(bState);
                            Object idObj = getIdMethod.invoke(blockReg, block);
                            String blockId = idObj != null ? idObj.toString().toLowerCase() : "";

                            if (isSeatBlock(blockId)) {
                                boolean occupied = false;
                                if (isOccupiedMethod != null) {
                                    try {
                                        occupied = (Boolean) isOccupiedMethod.invoke(null, world, bp);
                                    } catch (Throwable ignored) {}
                                }
                                if (!occupied) {
                                    double dSq = (px - (cx + 0.5)) * (px - (cx + 0.5))
                                               + (py - cy) * (py - cy)
                                               + (pz - (cz + 0.5)) * (pz - (cz + 0.5));
                                    if (dSq < bestDistSq) {
                                        bestDistSq = dSq;
                                        bestX = cx;
                                        bestY = cy;
                                        bestZ = cz;
                                    }
                                }
                            }
                        }
                    }
                }

                if (bestX == Integer.MIN_VALUE) {
                    return "ERR|NO_SEAT_FOUND";
                }
                targetX = bestX;
                targetY = bestY;
                targetZ = bestZ;
            }

            final int fx = targetX;
            final int fy = targetY;
            final int fz = targetZ;

            AtomicReference<String> resultRef = new AtomicReference<>("OK|" + fx + "|" + fy + "|" + fz);

            execute(server, () -> {
                try {
                    try {
                        Method stopRidingMethod = playerEntity.getClass().getMethod("method_5848");
                        stopRidingMethod.invoke(playerEntity);
                    } catch (Throwable ignored) {}

                    Object bp = bpCls.getConstructor(int.class, int.class, int.class).newInstance(fx, fy, fz);
                    Class<?> worldCls = Class.forName("net.minecraft.class_1937", true, cl);
                    Class<?> entCls = Class.forName("net.minecraft.class_1297", true, cl);

                    boolean sat = false;
                    try {
                        Class<?> seatBlockCls = Class.forName("com.starfish_studios.another_furniture.block.SeatBlock", true, cl);
                        Method sitDownMethod = seatBlockCls.getMethod("sitDown", worldCls, bpCls, entCls);
                        sitDownMethod.invoke(null, world, bp, playerEntity);
                        sat = true;
                    } catch (Throwable t) {
                        System.out.println("[SyntheticSeatManager] sitDown fallback: " + t);
                    }

                    if (!sat) {
                        Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
                        Object box = boxClass.getConstructor(
                                double.class, double.class, double.class, double.class, double.class, double.class)
                                .newInstance(fx, fy, fz, fx + 1.0, fy + 1.0, fz + 1.0);
                        java.util.List<?> ents = (java.util.List<?>) world.getClass()
                                .getMethod("method_8335", entCls, boxClass)
                                .invoke(world, null, box);
                        if (ents != null) {
                            for (Object e : ents) {
                                if (e != null && e != playerEntity && e.getClass().getSimpleName().contains("Seat")) {
                                    Method startRiding = playerEntity.getClass().getMethod("method_5804", entCls);
                                    startRiding.invoke(playerEntity, e);
                                    sat = true;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    resultRef.set("ERR|" + t.getMessage());
                }
            });

            return resultRef.get();
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    public static String stand(Object server, Object playerEntity, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            execute(server, () -> {
                try {
                    Method stopRidingMethod = null;
                    try {
                        stopRidingMethod = playerEntity.getClass().getMethod("method_5848");
                    } catch (NoSuchMethodException e) {
                        stopRidingMethod = playerEntity.getClass().getMethod("stopRiding");
                    }
                    if (stopRidingMethod != null) {
                        stopRidingMethod.invoke(playerEntity);
                    }
                } catch (Throwable t) {
                    System.out.println("[SyntheticSeatManager] stand error: " + t);
                }
            });
            return "OK";
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    public static String isSitting(Object playerEntity, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            Method hasVehicleMethod = playerEntity.getClass().getMethod("method_5765");
            boolean riding = (Boolean) hasVehicleMethod.invoke(playerEntity);
            return "OK|" + riding;
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    private static void execute(Object server, Runnable task) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Method execMethod = server.getClass().getMethod("execute", Runnable.class);
            execMethod.invoke(server, (Runnable) () -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
            latch.await(2, TimeUnit.SECONDS);
        } catch (Throwable t) {
            task.run();
        }
    }

    private static boolean isSeatBlock(String blockId) {
        if (blockId == null) return false;
        String id = blockId.toLowerCase();
        return id.contains("sofa") || id.contains("chair") || id.contains("bench") || id.contains("stool");
    }
}
