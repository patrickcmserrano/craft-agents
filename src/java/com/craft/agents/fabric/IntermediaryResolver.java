package com.craft.agents.fabric;

import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolvedor e cache de MethodHandles para mapeamentos Intermediary e Mojmap no Fabric 1.21.1 (CA-006).
 */
public final class IntermediaryResolver {
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, MethodHandle> HANDLES_CACHE = new ConcurrentHashMap<>();

    private IntermediaryResolver() {}

    public static Map<String, MethodHandle> resolveCoreHandles(ClassLoader targetLoader) {
        return Collections.unmodifiableMap(HANDLES_CACHE);
    }

    public static Class<?> resolveMinecraftClass(ClassLoader loader, String mojmapName, String intermediaryName) {
        String key = mojmapName + "|" + intermediaryName;
        return CLASS_CACHE.computeIfAbsent(key, k -> {
            ClassLoader cl = loader != null ? loader : Thread.currentThread().getContextClassLoader();
            try {
                return Class.forName(mojmapName, false, cl);
            } catch (ClassNotFoundException e1) {
                try {
                    return Class.forName(intermediaryName, false, cl);
                } catch (ClassNotFoundException e2) {
                    return null;
                }
            }
        });
    }
}
