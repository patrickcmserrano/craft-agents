package com.craft.agents.attach;

import com.craft.agents.fabric.SyntheticPlayerFactory;
import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;

/**
 * Agente de hot-reload que redefine SyntheticPlayerFactory a partir do disco,
 * injeta classes auxiliares no KnotClassLoader e garante a Bridge :25595 ativa.
 */
public final class BridgeReloadAgent {
    private BridgeReloadAgent() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        try {
            File jarFile = new File("/home/pace/dev/craft-agents/target/craft-agents.jar");
            if (jarFile.exists() && inst != null) {
                try {
                    inst.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(jarFile));
                    System.out.println("[craft-agents] Appended craft-agents.jar to SystemClassLoader!");
                } catch (Throwable t) {
                    System.err.println("[craft-agents] Erro appendToSystemClassLoaderSearch: " + t.getMessage());
                }
            }

            ClassLoader targetCl = SyntheticPlayerFactory.class.getClassLoader();
            Method defMethod = null;
            try {
                defMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                defMethod.setAccessible(true);
            } catch (Throwable ignored) {}

            File root = new File("/home/pace/dev/craft-agents/target/classes");
            if (root.exists() && defMethod != null && targetCl != null) {
                loadClassesRecursively(root, root, targetCl, defMethod);
            }

            File clsFile = new File("/home/pace/dev/craft-agents/target/classes/com/craft/agents/fabric/SyntheticPlayerFactory.class");
            if (clsFile.exists() && inst != null && inst.isRedefineClassesSupported()) {
                byte[] bytes = Files.readAllBytes(clsFile.toPath());
                inst.redefineClasses(new ClassDefinition(SyntheticPlayerFactory.class, bytes));
                System.out.println("[craft-agents] SyntheticPlayerFactory hot-redefined com sucesso!");
            }
        } catch (Throwable t) {
            System.err.println("[craft-agents] Aviso ao redefinir SyntheticPlayerFactory: " + t.getMessage());
        }
        CraftAgentsBootstrap.agentmain(agentArgs, inst);
    }

    private static void loadClassesRecursively(File base, File dir, ClassLoader cl, Method defMethod) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                loadClassesRecursively(base, f, cl, defMethod);
            } else if (f.getName().endsWith(".class") && !f.getName().equals("SyntheticPlayerFactory.class") && !f.getName().equals("BridgeReloadAgent.class")) {
                try {
                    String rel = base.toPath().relativize(f.toPath()).toString();
                    String className = rel.replace(File.separatorChar, '.').replaceAll("\\.class$", "");
                    try {
                        cl.loadClass(className);
                    } catch (ClassNotFoundException e) {
                        byte[] bytes = Files.readAllBytes(f.toPath());
                        defMethod.invoke(cl, className, bytes, 0, bytes.length);
                        System.out.println("[craft-agents] Definida classe no Knot: " + className);
                    }
                } catch (Throwable t) {
                    System.err.println("[craft-agents] Erro ao definir " + f.getName() + ": " + t.getMessage());
                }
            }
        }
    }
}
