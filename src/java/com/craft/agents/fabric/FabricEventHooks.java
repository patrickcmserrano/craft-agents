package com.craft.agents.fabric;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Hooks nativos de eventos de chat e dano na Fabric API (CA-011).
 */
public final class FabricEventHooks {
    private static final List<Consumer<Map<String, Object>>> LISTENERS = new CopyOnWriteArrayList<>();

    private FabricEventHooks() {}

    public static void registerChatAndDamageListeners(Consumer<Map<String, Object>> eventSink) {
        if (eventSink != null) {
            LISTENERS.add(eventSink);
        }
    }

    public static void dispatchEvent(Map<String, Object> event) {
        for (Consumer<Map<String, Object>> listener : LISTENERS) {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {}
        }
    }
}
