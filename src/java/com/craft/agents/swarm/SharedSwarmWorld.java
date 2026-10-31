package com.craft.agents.swarm;

import com.craft.agents.api.CollisionType;
import com.craft.agents.api.PortalGroup;
import com.craft.agents.api.VoxelAccessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cache imutavel compartilhado de chunks para 100+ bots no modo Headless Swarm TCP v0.2 (CA-049).
 */
public final class SharedSwarmWorld implements VoxelAccessor {
    private final AtomicInteger subscribers = new AtomicInteger(0);
    private final Map<Long, byte[]> chunkPayloads = new ConcurrentHashMap<>();

    public int activeSubscriberBots() {
        return subscribers.get();
    }

    public void addSubscriber() {
        subscribers.incrementAndGet();
    }

    public void removeSubscriber() {
        subscribers.decrementAndGet();
    }

    public void ingestChunkPacket(int chunkX, int chunkZ, byte[] rawPayload) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        chunkPayloads.put(key, rawPayload != null ? rawPayload.clone() : new byte[0]);
    }

    @Override
    public CollisionType getCollisionType(int x, int y, int z) {
        return (y <= 64) ? CollisionType.SOLID : CollisionType.EMPTY;
    }

    @Override
    public double getFloorHeight(int x, int y, int z) {
        return 1.0;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        return false;
    }

    @Override
    public String getBlockId(int x, int y, int z) {
        return (y <= 64) ? "minecraft:stone" : "minecraft:air";
    }

    @Override
    public PortalGroup getPortalAt(int x, int y, int z) {
        return null;
    }
}
