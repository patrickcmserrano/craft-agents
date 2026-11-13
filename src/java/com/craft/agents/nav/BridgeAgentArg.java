package com.craft.agents.nav;

/**
 * Parse do campo opcional {@code agent=<Nome>} no fim dos comandos da ponte
 * (PATH/GROUND/BLOCK/TOGGLE_PORTAL, CA-095). Sem o campo, o padrao e "Karlach"
 * (compatibilidade). Classe separada da SyntheticPlayerFactory: carregada pelo
 * FreshNavClassLoader, nao exige redefinir a factory.
 */
public final class BridgeAgentArg {
    public static final String DEFAULT_AGENT = "Karlach";
    private static final String PREFIX = "agent=";

    private BridgeAgentArg() {}

    public static boolean isAgentField(String s) {
        return s != null && s.trim().startsWith(PREFIX);
    }

    /** Nome do agente do ultimo campo {@code agent=}; vazio/ausente => "Karlach". */
    public static String agentName(String[] parts) {
        if (parts != null) {
            for (int i = parts.length - 1; i >= 1; i--) {
                if (isAgentField(parts[i])) {
                    String n = parts[i].trim().substring(PREFIX.length()).trim();
                    return n.isEmpty() ? DEFAULT_AGENT : n;
                }
            }
        }
        return DEFAULT_AGENT;
    }
}
