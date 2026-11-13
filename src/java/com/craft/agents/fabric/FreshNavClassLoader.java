package com.craft.agents.fabric;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * ClassLoader child-first so para as classes de navegacao (com.craft.agents.nav.*,
 * com.craft.agents.api.* e LiveWorldVoxelAccessor), para que os ops PATH/GROUND
 * sempre vejam a versao recem-compilada em disco. Todo o resto delega ao pai
 * (classes do Minecraft vem do Knot). Instancias sao cacheadas por versao do diretorio.
 */
public final class FreshNavClassLoader extends URLClassLoader {
    private static final String LIVE_VOX = "com.craft.agents.fabric.LiveWorldVoxelAccessor";

    private static FreshNavClassLoader cached;
    private static File cachedDir;
    private static ClassLoader cachedParent;
    private static long cachedVersion;

    private FreshNavClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    private static boolean childFirst(String name) {
        return name.startsWith("com.craft.agents.nav.")
            || name.startsWith("com.craft.agents.api.")
            || name.equals(LIVE_VOX);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!childFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private static long lastModifiedIn(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".class"));
        long max = 0L;
        if (files != null) {
            for (File f : files) max = Math.max(max, f.lastModified());
        }
        return max;
    }

    private static long versionOf(File classesDir) {
        long v = lastModifiedIn(new File(classesDir, "com/craft/agents/nav"));
        v = Math.max(v, lastModifiedIn(new File(classesDir, "com/craft/agents/api")));
        v = Math.max(v, new File(classesDir, "com/craft/agents/fabric/LiveWorldVoxelAccessor.class").lastModified());
        return v;
    }

    public static synchronized FreshNavClassLoader forDir(File classesDir, ClassLoader parent) {
        long version = versionOf(classesDir);
        if (cached != null && classesDir.equals(cachedDir) && parent == cachedParent && version == cachedVersion) {
            return cached;
        }
        try {
            cached = new FreshNavClassLoader(new URL[] { classesDir.toURI().toURL() }, parent);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        cachedDir = classesDir;
        cachedParent = parent;
        cachedVersion = version;
        return cached;
    }
}
