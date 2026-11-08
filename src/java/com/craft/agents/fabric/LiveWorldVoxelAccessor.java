package com.craft.agents.fabric;

import com.craft.agents.api.CollisionType;
import com.craft.agents.api.PortalGroup;
import com.craft.agents.api.VoxelAccessor;
import com.craft.agents.nav.PackedPos;

import java.util.HashMap;
import java.util.Map;

/**
 * VoxelAccessor que consulta blocos reais do ServerWorld do Fabric 1.21.1 em tempo de execucao.
 */
public final class LiveWorldVoxelAccessor implements VoxelAccessor {
    private final Object overworld;
    private final ClassLoader cl;
    private final Map<Long, CollisionType> collisionCache = new HashMap<>();
    private final Map<Long, String> blockIdCache = new HashMap<>();
    private final Map<Long, PortalGroup> portalCache = new HashMap<>();

    public LiveWorldVoxelAccessor(Object overworld, ClassLoader cl) {
        this.overworld = overworld;
        this.cl = cl;
    }

    private void inspectBlock(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        if (collisionCache.containsKey(p)) return;
        try {
            Class<?> bpCls = Class.forName("net.minecraft.class_2338", true, cl);
            Object bp = bpCls.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            Object bState = overworld.getClass().getMethod("method_8320", bpCls).invoke(overworld, bp);
            boolean isAir = (Boolean) bState.getClass().getMethod("method_26215").invoke(bState);
            if (isAir) {
                collisionCache.put(p, CollisionType.EMPTY);
                blockIdCache.put(p, "minecraft:air");
                return;
            }
            Object block = bState.getClass().getMethod("method_26204").invoke(bState);
            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object blockReg = regClass.getField("field_41175").get(null);
            Object idObj = blockReg.getClass().getMethod("method_10221", Object.class).invoke(blockReg, block);
            String id = idObj != null ? idObj.toString() : "minecraft:air";
            blockIdCache.put(p, id);

            boolean noCollision = false;
            try {
                Class<?> blockViewCls = Class.forName("net.minecraft.class_1922", true, cl);
                Object shape = bState.getClass().getMethod("method_26218", blockViewCls, bpCls).invoke(bState, overworld, bp);
                noCollision = (Boolean) shape.getClass().getMethod("method_1110").invoke(shape);
            } catch (Throwable ignored) {
                noCollision = isAir || id.equals("minecraft:light") || id.contains("air")
                        || id.contains("torch") || id.contains("flower") || id.contains("grass");
            }

            String stateStr = bState.toString().toLowerCase();
            boolean isOpen = stateStr.contains("open=true");

            if (id.contains("lava") || id.contains("fire") || id.contains("campfire") || id.contains("sweet_berry_bush")) {
                collisionCache.put(p, CollisionType.HAZARD);
            } else if (id.contains("fence_gate") || id.contains("_gate") || id.contains("_door")) {
                if (isOpen || noCollision) {
                    collisionCache.put(p, CollisionType.EMPTY);
                } else {
                    collisionCache.put(p, CollisionType.PORTAL);
                    boolean isIron = id.contains("iron_door");
                    PortalGroup pg = new PortalGroup(
                        "gate-" + x + "-" + y + "-" + z,
                        new long[]{p},
                        x + 0.5, y, z + 0.5,
                        1.0, 0.0,
                        !isIron, false
                    );
                    portalCache.put(p, pg);
                }
            } else if (id.contains("water")) {
                collisionCache.put(p, CollisionType.LIQUID);
            } else if (noCollision) {
                collisionCache.put(p, CollisionType.EMPTY);
            } else {
                collisionCache.put(p, CollisionType.SOLID);
            }
        } catch (Throwable t) {
            collisionCache.put(p, CollisionType.SOLID);
            blockIdCache.put(p, "minecraft:stone");
        }
    }

    @Override
    public CollisionType getCollisionType(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        if (!collisionCache.containsKey(p)) inspectBlock(x, y, z);
        return collisionCache.get(p);
    }

    @Override
    public double getFloorHeight(int x, int y, int z) {
        String id = getBlockId(x, y, z);
        if (id.contains("fence") || id.contains("wall")) return 1.5;
        return 1.0;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        return getCollisionType(x, y, z) == CollisionType.HAZARD;
    }

    @Override
    public String getBlockId(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        if (!blockIdCache.containsKey(p)) inspectBlock(x, y, z);
        return blockIdCache.get(p);
    }

    @Override
    public PortalGroup getPortalAt(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        if (!collisionCache.containsKey(p)) inspectBlock(x, y, z);
        return portalCache.get(p);
    }
}
