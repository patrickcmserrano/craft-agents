package com.craft.agents.fabric;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Interacao real com blocos (portas/portoes/baus) e alimentacao de entidades modded (CA-017, CA-018).
 */
public final class SyntheticInteraction {
    private SyntheticInteraction() {}

    public static boolean togglePortalBlock(Object worldAtom, int x, int y, int z, boolean targetOpen) {
        if (worldAtom == null) return false;
        try {
            Class<?> rtCls = Class.forName("clojure.lang.RT");
            Method varMethod = rtCls.getMethod("var", String.class, String.class);
            Object assocIn = varMethod.invoke(null, "clojure.core", "assoc-in");
            Object swapBang = varMethod.invoke(null, "clojure.core", "swap!");

            Class<?> kwCls = Class.forName("clojure.lang.Keyword");
            Method intern = kwCls.getMethod("intern", String.class);
            Object blocksKw = intern.invoke(null, "blocks");
            Object openKw = intern.invoke(null, "open?");

            Method vectorMethod = rtCls.getMethod("vector", Object[].class);
            Object coord = vectorMethod.invoke(null, (Object) new Object[]{x, y, z});
            Object path = vectorMethod.invoke(null, (Object) new Object[]{blocksKw, coord, openKw});

            Method invoke4 = swapBang.getClass().getMethod("invoke", Object.class, Object.class, Object.class, Object.class);
            invoke4.invoke(swapBang, worldAtom, assocIn, path, targetOpen);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean feedEntity(Object playerAtom, Object entityAtom, String itemId) {
        if (playerAtom == null || entityAtom == null) return false;
        try {
            Class<?> rtCls = Class.forName("clojure.lang.RT");
            Method varMethod = rtCls.getMethod("var", String.class, String.class);
            Object updateIn = varMethod.invoke(null, "clojure.core", "update-in");
            Object assocIn = varMethod.invoke(null, "clojure.core", "assoc-in");
            Object decFn = varMethod.invoke(null, "clojure.core", "dec");
            Object swapBang = varMethod.invoke(null, "clojure.core", "swap!");

            Class<?> kwCls = Class.forName("clojure.lang.Keyword");
            Method intern = kwCls.getMethod("intern", String.class);
            Object invKw = intern.invoke(null, "inventory");
            Object inLoveKw = intern.invoke(null, "in-love?");

            Method vectorMethod = rtCls.getMethod("vector", Object[].class);
            Object invPath = vectorMethod.invoke(null, (Object) new Object[]{invKw, itemId});
            Object lovePath = vectorMethod.invoke(null, (Object) new Object[]{inLoveKw});

            Method invoke4 = swapBang.getClass().getMethod("invoke", Object.class, Object.class, Object.class, Object.class);
            invoke4.invoke(swapBang, playerAtom, updateIn, invPath, decFn);
            invoke4.invoke(swapBang, entityAtom, assocIn, lovePath, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
