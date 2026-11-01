package com.craft.agents.attach;

import com.craft.agents.fabric.SyntheticPlayerFactory;
import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;

/**
 * Agente de hot-reload que redefine SyntheticPlayerFactory a partir do disco e garante a Bridge :25595 ativa.
 */
public final class BridgeReloadAgent {
    private BridgeReloadAgent() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        try {
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
}
