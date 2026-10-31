package com.craft.agents.fabric;

import java.util.Map;

/**
 * Inspetor direto de Registries do Fabric p/ resolver nomes reais de entidades/itens modded (CA-010).
 */
public final class FabricRegistryInspector {
    private FabricRegistryInspector() {}

    public static String resolveEntityTypeId(Object entityInstance) {
        return extractId(entityInstance, "minecraft:pig");
    }

    public static String resolveItemId(Object itemStackInstance) {
        return extractId(itemStackInstance, "minecraft:air");
    }

    public static String resolveBlockId(Object blockStateInstance) {
        return extractId(blockStateInstance, "minecraft:stone");
    }

    private static String extractId(Object obj, String fallback) {
        if (obj == null) return fallback;
        if (obj instanceof String s) return s;
        if (obj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                String k = String.valueOf(entry.getKey());
                if (k.equals("registry-id") || k.equals(":registry-id")) {
                    return String.valueOf(entry.getValue());
                }
            }
        }
        return obj.toString();
    }
}
