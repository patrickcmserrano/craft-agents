package com.craft.agents.attach;

import java.io.StringReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agente JVM Zero-Install com injecao idempotente no KnotClassLoader (CA-007).
 */
public final class CraftAgentsBootstrap {
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

    private CraftAgentsBootstrap() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!BOOTSTRAPPED.compareAndSet(false, true)) {
            System.out.println("[craft-agents] Agente ja inicializado no processo alvo.");
            return;
        }

        int port = 8095;
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            try {
                port = Integer.parseInt(agentArgs.trim());
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("[craft-agents] Zero-Install Agent anexado com sucesso! Inicializando Agent Studio na porta " + port + "...");

        final int studioPort = port;
        Thread studioThread = new Thread(() -> {
            try {
                ClassLoader knotLoader = null;
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    ClassLoader cl = t.getContextClassLoader();
                    if (cl != null && cl.getClass().getName().contains("Knot")) {
                        knotLoader = cl;
                        break;
                    }
                }
                if (knotLoader == null) {
                    knotLoader = Thread.currentThread().getContextClassLoader();
                }
                Thread.currentThread().setContextClassLoader(knotLoader);

                Class<?> compilerClass = Class.forName("clojure.lang.Compiler", true, knotLoader);
                Method loadMethod = compilerClass.getMethod("load", java.io.Reader.class);
                String script =
                    "(try " +
                    "  (require 'craft.agents.studio.server) " +
                    "  (craft.agents.studio.server/start-studio-server! {:tick 0} " + studioPort + ") " +
                    "  (catch Throwable t " +
                    "    (println \"[craft-agents] Erro ao iniciar Agent Studio no Knot:\" (.getMessage t))))";
                loadMethod.invoke(null, new StringReader(script));
            } catch (Throwable t) {
                System.out.println("[craft-agents] Aviso durante boot in-process: " + t.getMessage());
            }
        }, "craft-agents-studio-boot");
        studioThread.setDaemon(true);
        studioThread.start();
    }

    public static boolean isBootstrapped() {
        return BOOTSTRAPPED.get();
    }
}
