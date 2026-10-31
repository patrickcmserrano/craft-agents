package com.craft.agents.fabric;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Canal Netty in-memory silencioso que libera pacotes sem serializacao/rede (CA-013).
 */
public final class SilentEmbeddedChannel {
    private final AtomicLong discarded = new AtomicLong(0);

    public boolean releaseOutboundMessage(Object msg) {
        discarded.incrementAndGet();
        return true;
    }

    public long discardedPacketCount() {
        return discarded.get();
    }
}
