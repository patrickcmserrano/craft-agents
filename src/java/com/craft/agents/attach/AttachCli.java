package com.craft.agents.attach;

import com.sun.tools.attach.VirtualMachine;
import java.io.File;

/**
 * Harness CLI via Attach API para anexar o craft-agents ao PID do Minecraft (CA-007).
 */
public final class AttachCli {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: AttachCli <pid> <agentJarPath> [studioPort]");
            System.exit(1);
        }
        String pid = args[0];
        String jarPath = new File(args[1]).getAbsolutePath();
        String port = args.length >= 3 ? args[2] : "8095";

        System.out.println("[craft-agents] Anexando agente ao PID " + pid + " (JAR: " + jarPath + ", Studio Port: " + port + ")...");
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(jarPath, port);
            vm.detach();
            System.out.println("[craft-agents] Agente injetado com sucesso no processo " + pid + "!");
        } catch (Throwable t) {
            System.err.println("[-] Falha ao anexar agente JVM: " + t.getMessage());
            t.printStackTrace();
            System.exit(2);
        }
    }
}
