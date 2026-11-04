package com.craft.agents.fabric;

/**
 * Utilitario para inspecao de nomes, IDs e comparacao de entidades (CA-040).
 */
public final class EntityTargetResolver {
    private EntityTargetResolver() {}

    public static String getEntityRegistryIdOrName(Object entity, ClassLoader cl) {
        if (entity == null) return "unknown";
        try {
            Object typeObj = entity.getClass().getMethod("method_5864").invoke(entity);
            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object entReg = regClass.getField("field_41177").get(null);
            Object idObj = entReg.getClass().getMethod("method_10221", Object.class).invoke(entReg, typeObj);
            if (idObj != null) {
                return idObj.toString();
            }
        } catch (Throwable ignored) {}
        return entity.getClass().getSimpleName();
    }

    public static boolean entityMatchesTarget(Object entity, String targetType, ClassLoader cl) {
        if (entity == null) return false;
        if (targetType == null || targetType.trim().isEmpty()) return true;
        String target = targetType.trim().toLowerCase();

        // 1. Custom Name (ex: "Raposa das Neves", "Capivarinha Filhote")
        try {
            Object nameObj = entity.getClass().getMethod("method_5477").invoke(entity);
            if (nameObj != null) {
                String customName = ((String) nameObj.getClass().getMethod("getString").invoke(nameObj)).toLowerCase();
                if (customName.contains(target) || target.contains(customName)) return true;
            }
        } catch (Throwable ignored) {}

        // 2. EntityType registry id (ex: "minecraft:fox", "naturalist:capybara")
        String regId = getEntityRegistryIdOrName(entity, cl).toLowerCase();
        if (regId.contains(target) || target.contains(regId)) return true;
        String path = regId.contains(":") ? regId.substring(regId.indexOf(':') + 1) : regId;
        if (path.contains(target) || target.contains(path)) return true;

        // 3. Simple class name
        String cName = entity.getClass().getSimpleName().toLowerCase();
        if (cName.contains(target) || target.contains(cName)) return true;

        return false;
    }
}
