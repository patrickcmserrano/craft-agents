package com.craft.agents.attach;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * Sobe um servidor nREPL DENTRO da JVM do servidor Minecraft, no mesmo
 * KnotClassLoader que carrega o engine (SyntheticPlayerFactory e afins).
 * Assim Clojure avaliado por essa porta alcança o estado vivo dos agentes
 * server-native (pose, entidade, mundo) — o que o nREPL do craft-repl (7888)
 * NÃO alcança, porque nasce noutro classloader.
 *
 * Porta padrao: 7890.
 *
 * Origem do desenho: o bootstrap provado no craft-repl (CraftAgent.java) —
 * sem o appendToSystemClassLoaderSearch + o loader explicito, o Class.forName
 * de clojure.lang.RT morre com ClassNotFoundException; e sem fixar o
 * contextClassLoader antes de criar o servidor, as threads do ThreadPoolExecutor
 * do nREPL nascem sem o Knot e o envio da resposta falha em print.clj:94.
 */
public final class NreplBootstrap {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final int DEFAULT_PORT = 7890;
    private static final String JARS_LIST = "/tmp/clojure_jars.txt";

    private NreplBootstrap() {}

    public static void start(Instrumentation inst, int port) {
        if (!STARTED.compareAndSet(false, true)) {
            System.out.println("[craft-agents] nREPL ja iniciado nesta JVM (porta " + port + ").");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ClassLoader knotLoader = findKnotLoader();
                Thread.currentThread().setContextClassLoader(knotLoader);
                System.out.println("[craft-agents] nREPL target loader: " + knotLoader.getClass().getName());

                registerClojureJars(inst, knotLoader);

                Class<?> compilerClass = Class.forName("clojure.lang.Compiler", true, knotLoader);
                Method loadMethod = compilerClass.getMethod("load", java.io.Reader.class);

                // Os handlers do nREPL herdam o contextClassLoader da thread que
                // CRIA o servidor. Fixar o Knot aqui faz o pool herdar o loader certo.
                Thread.currentThread().setContextClassLoader(knotLoader);

                String script =
                    "(try " +
                    "  (require 'nrepl.server) " +
                    "  (let [start-srv (resolve 'nrepl.server/start-server)] " +
                    "    (defonce craft-agents-nrepl " +
                    "      (start-srv :port " + port + " :bind \"127.0.0.1\")) " +
                    "    (println \"[craft-agents] nREPL escutando em 127.0.0.1:\" " + port + ")) " +
                    "  (catch Throwable t " +
                    "    (println \"[craft-agents] Erro ao iniciar nREPL:\" (.getMessage t)) " +
                    "    (.printStackTrace t)))";
                loadMethod.invoke(null, new StringReader(script));
            } catch (Throwable err) {
                System.err.println("[craft-agents] Falha ao subir nREPL: " + err);
                err.printStackTrace();
                STARTED.set(false);
            }
        }, "craft-agents-nrepl-boot");
        t.setDaemon(true);
        t.start();
    }

    public static void start(Instrumentation inst) {
        start(inst, DEFAULT_PORT);
    }

    private static ClassLoader findKnotLoader() {
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = th.getContextClassLoader();
            if (cl != null && cl.getClass().getName().contains("Knot")) {
                return cl;
            }
        }
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * Injeta o classpath (JARs E diretorios, ex. src/clj) no KnotClassLoader.
     * Sem o appendToSystemClassLoaderSearch o Class.forName("clojure.lang.RT", ..., knot)
     * morre com ClassNotFoundException mesmo com o URL registrado (medido no craft-repl).
     * Diretorios (src/clj, resources, target/classes) sao necessarios para o nREPL
     * in-server enxergar os namespaces craft.agents.* e as classes compiladas.
     */
    private static void registerClojureJars(Instrumentation inst, ClassLoader knotLoader) throws Exception {
        File jarsListFile = new File(JARS_LIST);
        if (!jarsListFile.exists()) {
            throw new IllegalStateException("Lista de classpath ausente: " + JARS_LIST);
        }

        Field delegateField = knotLoader.getClass().getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(knotLoader);
        Field vpcsField = delegate.getClass().getDeclaredField("validParentCodeSources");
        vpcsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Path> vpcs = (Set<Path>) vpcsField.get(delegate);
        Method addUrlFwd = knotLoader.getClass().getDeclaredMethod("addUrlFwd", java.net.URL.class);
        addUrlFwd.setAccessible(true);

        int jars = 0;
        int dirs = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(jarsListFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                File entry = new File(line);
                if (!entry.isAbsolute() || !entry.exists()) continue;
                if (entry.isFile() && inst != null) {
                    inst.appendToSystemClassLoaderSearch(new JarFile(entry));
                    jars++;
                } else if (entry.isDirectory()) {
                    dirs++;
                }
                vpcs.add(entry.toPath());
                try {
                    addUrlFwd.invoke(knotLoader, entry.toURI().toURL());
                } catch (Throwable t) {
                    System.err.println("[craft-agents] AVISO addUrlFwd falhou em " + entry.getName() + ": " + t);
                }
            }
        }
        System.out.println("[craft-agents] " + jars + " JARs + " + dirs
                + " diretorios registrados no Knot para o nREPL in-server.");
    }
}
