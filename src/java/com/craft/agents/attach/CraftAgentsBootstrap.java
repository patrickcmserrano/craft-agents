package com.craft.agents.attach;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agente JVM Zero-Install com injecao idempotente no KnotClassLoader (CA-007).
 */
public final class CraftAgentsBootstrap {
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

    private CraftAgentsBootstrap() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (BOOTSTRAPPED.compareAndSet(false, true)) {
            System.out.println("[craft-agents] Zero-Install Agent anexado com sucesso.");
        }
    }

    public static boolean isBootstrapped() {
        return BOOTSTRAPPED.get();
    }
}
