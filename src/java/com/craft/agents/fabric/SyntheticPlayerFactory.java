package com.craft.agents.fabric;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabrica de ServerPlayerEntity sinteticos acoplados ao SilentEmbeddedChannel (CA-014)
 * e ponte nativa in-server na porta 25595 para controle 20Hz sem lag.
 */
public final class SyntheticPlayerFactory {
    private static final AtomicBoolean BRIDGE_RUNNING = new AtomicBoolean(false);
    private static volatile Object cachedServer = null;
    private static volatile ClassLoader cachedKnotLoader = null;

    private SyntheticPlayerFactory() {}

    public static UUID offlineAgentUuid(String agentName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + agentName).getBytes(StandardCharsets.UTF_8));
    }

    public static Object spawnSyntheticPlayer(Object server, String agentName, String dimension, double x, double y, double z) {
        if (server == null) return null;
        try {
            return spawnOrUpdateOnServer(server, server.getClass().getClassLoader(), agentName, x, y, z, "", "");
        } catch (Throwable t) {
            return null;
        }
    }

    public static void removeSyntheticPlayer(Object server, Object playerEntity) {
        if (server == null || playerEntity == null) return;
        try {
            Object pm = server.getClass().getMethod("method_3760").invoke(server);
            for (Method m : pm.getClass().getMethods()) {
                if (m.getName().equals("method_14611") && m.getParameterCount() == 1) {
                    m.invoke(pm, playerEntity);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void startInServerBridge(Instrumentation inst, int bridgePort) {
        if (!BRIDGE_RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread serverThread = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(bridgePort, 50, InetAddress.getByName("127.0.0.1"))) {
                System.out.println("[craft-agents] In-Server Fabric Bridge ativo em 127.0.0.1:" + bridgePort);
                while (true) {
                    Socket client = ss.accept();
                    client.setTcpNoDelay(true);
                    handleClientConnection(client, inst);
                }
            } catch (Throwable t) {
                System.err.println("[craft-agents] Erro no In-Server Bridge: " + t.getMessage());
                BRIDGE_RUNNING.set(false);
            }
        }, "craft-agents-fabric-bridge");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void handleClientConnection(Socket client, Instrumentation inst) {
        Thread worker = new Thread(() -> {
            try (Socket c = client;
                 BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8), true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    String resp = processCommand(line, inst);
                    out.println(resp);
                }
            } catch (Throwable ignored) {}
        }, "craft-agents-bridge-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static Object resolveServerInstance(Instrumentation inst) {
        if (cachedServer != null) return cachedServer;
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().contains("Server thread") || t.getName().contains("VoiceChatServerThread")) {
                    for (Field f : t.getClass().getDeclaredFields()) {
                        String typeName = f.getType().getName();
                        if (typeName.contains("3176") || typeName.contains("1132")
                                || typeName.contains("MinecraftServer") || typeName.contains("DedicatedServer")) {
                            f.setAccessible(true);
                            Object s = f.get(t);
                            if (s != null) {
                                cachedServer = s;
                                cachedKnotLoader = s.getClass().getClassLoader();
                                return s;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (inst == null) return null;
        try {
            Class<?> mcServerClass = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().equals("net.minecraft.server.MinecraftServer")) {
                    mcServerClass = c;
                    cachedKnotLoader = c.getClassLoader();
                    break;
                }
            }
            if (mcServerClass == null) return null;

            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (mcServerClass.isAssignableFrom(c) && !c.equals(mcServerClass)) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) && mcServerClass.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (v != null) {
                                cachedServer = v;
                                return v;
                            }
                        }
                    }
                }
            }
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().startsWith("net.minecraft.") || c.getName().startsWith("net.fabricmc.") || c.getName().startsWith("org.samo_lego.")) {
                    try {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) && mcServerClass.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                Object v = f.get(null);
                                if (v != null) {
                                    cachedServer = v;
                                    return v;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return cachedServer;
    }

    private static void runOnServerThread(Object server, Runnable task) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Method execMethod = server.getClass().getMethod("execute", Runnable.class);
        execMethod.invoke(server, (Runnable) () -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(1500, TimeUnit.MILLISECONDS);
    }

    private static Object spawnOrUpdateOnServer(
            Object server,
            ClassLoader cl,
            String name,
            double x,
            double y,
            double z,
            String skinVal,
            String skinSig) throws Exception {

        Object playerManager = server.getClass().getMethod("method_3760").invoke(server);
        Method getPlayerMethod = playerManager.getClass().getMethod("method_14566", String.class);
        Object existing = getPlayerMethod.invoke(playerManager, name);
        final Object[] holder = new Object[] { existing };

        runOnServerThread(server, () -> {
            try {
                Object player = holder[0];
                if (player == null) {
                    Object overworld = server.getClass().getMethod("method_30002").invoke(server);
                    Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile", true, cl);
                    UUID uuid = offlineAgentUuid(name);
                    Object profile = gameProfileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);

                    Class<?> syncedOptionsClass = Class.forName("net.minecraft.class_8791", true, cl);
                    Object clientOptions = syncedOptionsClass.getMethod("method_53821").invoke(null);

                    Class<?> serverPlayerClass = Class.forName("net.minecraft.class_3222", true, cl);
                    Class<?> mcServerClass = Class.forName("net.minecraft.server.MinecraftServer", true, cl);
                    Class<?> serverWorldClass = Class.forName("net.minecraft.class_3218", true, cl);

                    Constructor<?> spCtor = serverPlayerClass.getConstructor(
                            mcServerClass, serverWorldClass, gameProfileClass, syncedOptionsClass);
                    player = spCtor.newInstance(server, overworld, profile, clientOptions);

                    Method setPosAngles = player.getClass().getMethod(
                            "method_5808", double.class, double.class, double.class, float.class, float.class);
                    setPosAngles.invoke(player, x, y, z, 0.0f, 0.0f);

                    Class<?> netSideClass = Class.forName("net.minecraft.class_2598", true, cl);
                    Object serverbound = netSideClass.getField("field_11941").get(null);
                    Class<?> clientConnClass = Class.forName("net.minecraft.class_2535", true, cl);
                    Object clientConn = clientConnClass.getConstructor(netSideClass).newInstance(serverbound);

                    Class<?> embeddedChannelClass = Class.forName("io.netty.channel.embedded.EmbeddedChannel", true, cl);
                    Class<?> channelHandlerClass = Class.forName("io.netty.channel.ChannelHandler", true, cl);
                    Object emptyHandlers = java.lang.reflect.Array.newInstance(channelHandlerClass, 0);
                    Object embeddedCh = embeddedChannelClass.getConstructor(emptyHandlers.getClass())
                            .newInstance(emptyHandlers);

                    Class<?> channelClass = Class.forName("io.netty.channel.Channel", true, cl);
                    for (Field f : clientConnClass.getDeclaredFields()) {
                        if (channelClass.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            f.set(clientConn, embeddedCh);
                            break;
                        }
                    }

                    Class<?> connectedClientDataClass = Class.forName("net.minecraft.class_8792", true, cl);
                    Object clientData = null;
                    for (Method m : connectedClientDataClass.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) && m.getReturnType().equals(connectedClientDataClass)) {
                            m.setAccessible(true);
                            if (m.getParameterCount() == 2) {
                                clientData = m.invoke(null, profile, false);
                            } else if (m.getParameterCount() == 3) {
                                clientData = m.invoke(null, profile, 0, clientOptions);
                            }
                            break;
                        }
                    }
                    if (clientData == null) {
                        Constructor<?> ccdCtor = connectedClientDataClass.getDeclaredConstructors()[0];
                        ccdCtor.setAccessible(true);
                        clientData = ccdCtor.newInstance(profile, 0, clientOptions, false);
                    }

                    Method onPlayerConnect = playerManager.getClass().getMethod(
                            "method_14570", clientConnClass, serverPlayerClass, connectedClientDataClass);
                    onPlayerConnect.invoke(playerManager, clientConn, player, clientData);
                    holder[0] = player;
                }

                Method setPosAngles = player.getClass().getMethod(
                        "method_5808", double.class, double.class, double.class, float.class, float.class);
                setPosAngles.invoke(player, x, y, z, 0.0f, 0.0f);
                player.getClass().getMethod("method_5660", boolean.class).invoke(player, false);

                if (skinVal != null && !skinVal.isEmpty()) {
                    for (Method m : player.getClass().getMethods()) {
                        if (m.getName().equals("fabrictailor_setSkin") && m.getParameterCount() == 3) {
                            m.invoke(player, skinVal, skinSig, true);
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
        return holder[0];
    }

    private static String processCommand(String line, Instrumentation inst) {
        try {
            if (line == null || line.isEmpty()) return "ERR|EMPTY";
            if (line.equals("PING")) return "PONG";

            Object server = resolveServerInstance(inst);
            if (server == null) return "ERR|NO_SERVER";
            ClassLoader cl = cachedKnotLoader != null ? cachedKnotLoader : server.getClass().getClassLoader();

            String[] parts = line.split("\\|", -1);
            String op = parts[0];

            if ("SPAWN".equals(op) && parts.length >= 7) {
                String name = parts[1];
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                double z = Double.parseDouble(parts[4]);
                String skinVal = parts[5];
                String skinSig = parts[6];
                spawnOrUpdateOnServer(server, cl, name, x, y, z, skinVal, skinSig);
                return "OK|" + x + "|" + y + "|" + z;
            }

            if ("DESPAWN".equals(op) && parts.length >= 2) {
                String name = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, name);
                if (player != null) {
                    runOnServerThread(server, () -> removeSyntheticPlayer(server, player));
                }
                return "OK";
            }

            if ("MOVE".equals(op) && parts.length >= 9) {
                String name = parts[1];
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                double z = Double.parseDouble(parts[4]);
                float yaw = Float.parseFloat(parts[5]);
                float pitch = Float.parseFloat(parts[6]);
                boolean sprint = Boolean.parseBoolean(parts[7]);
                boolean sneak = Boolean.parseBoolean(parts[8]);

                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, name);
                if (player == null) return "ERR|NOT_FOUND";

                runOnServerThread(server, () -> {
                    try {
                        player.getClass().getMethod(
                                "method_5808", double.class, double.class, double.class, float.class, float.class)
                                .invoke(player, x, y, z, yaw, pitch);
                        player.getClass().getMethod("method_5847", float.class).invoke(player, yaw);
                        try {
                            Field bodyYawField = Class.forName("net.minecraft.class_1309", true, cl).getDeclaredField("field_6241");
                            bodyYawField.setAccessible(true);
                            bodyYawField.setFloat(player, yaw);
                        } catch (Throwable ignored) {}
                        player.getClass().getMethod("method_5728", boolean.class).invoke(player, sprint);
                        player.getClass().getMethod("method_5660", boolean.class).invoke(player, sneak);
                        try {
                            Object overworld = player.getClass().getMethod("method_51469").invoke(player);
                            Object chunkMgr = overworld.getClass().getMethod("method_14178").invoke(overworld);
                            Class<?> spClass = Class.forName("net.minecraft.class_3222", true, cl);
                            chunkMgr.getClass().getMethod("method_14096", spClass).invoke(chunkMgr, player);
                        } catch (Throwable ignored) {}
                        try {
                            Field netHandlerField = player.getClass().getField("field_13987");
                            Object netHandler = netHandlerField.get(player);
                            if (netHandler != null) {
                                netHandler.getClass().getMethod("method_14372").invoke(netHandler);
                            }
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                });
                return "OK";
            }

            if ("SWING".equals(op) && parts.length >= 2) {
                String name = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, name);
                if (player != null) {
                    runOnServerThread(server, () -> {
                        try {
                            Class<?> handClass = Class.forName("net.minecraft.class_1268", true, cl);
                            Object mainHand = handClass.getField("field_5808").get(null);
                            player.getClass().getMethod("method_6104", handClass).invoke(player, mainHand);
                        } catch (Throwable ignored) {}
                    });
                }
                return "OK";
            }

            if ("CMD".equals(op) && parts.length >= 2) {
                String cmd = line.substring(4);
                runOnServerThread(server, () -> {
                    try {
                        Object cmdMgr = server.getClass().getMethod("method_3734").invoke(server);
                        Object cmdSrc = server.getClass().getMethod("method_3739").invoke(server);
                        Object silentSrc = cmdSrc.getClass().getMethod("method_9217").invoke(cmdSrc);
                        cmdMgr.getClass().getMethod("method_44252", cmdSrc.getClass(), String.class)
                                .invoke(cmdMgr, silentSrc, cmd);
                    } catch (Throwable ignored) {}
                });
                return "OK";
            }

            if ("PLAYERS".equals(op)) {
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                List<?> list = (List<?>) pm.getClass().getMethod("method_14571").invoke(pm);
                StringBuilder sb = new StringBuilder("OK|");
                boolean first = true;
                for (Object p : list) {
                    Object gp = p.getClass().getMethod("method_7334").invoke(p);
                    String pName = (String) gp.getClass().getMethod("getName").invoke(gp);
                    double px = (Double) p.getClass().getMethod("method_23317").invoke(p);
                    double py = (Double) p.getClass().getMethod("method_23318").invoke(p);
                    double pz = (Double) p.getClass().getMethod("method_23321").invoke(p);
                    if (!first) sb.append(";");
                    sb.append(pName).append(",").append(px).append(",").append(py).append(",").append(pz);
                    first = false;
                }
                return sb.toString();
            }

            if ("WEATHER".equals(op)) {
                Object overworld = server.getClass().getMethod("method_30002").invoke(server);
                long tod = (Long) overworld.getClass().getMethod("method_8510").invoke(overworld);
                boolean rain = (Boolean) overworld.getClass().getMethod("method_8419").invoke(overworld);
                boolean thunder = (Boolean) overworld.getClass().getMethod("method_8546").invoke(overworld);
                int rainTime = 0;
                int clearTime = 0;
                try {
                    Field propsField = overworld.getClass().getDeclaredField("field_24456");
                    propsField.setAccessible(true);
                    Object props = propsField.get(overworld);
                    rainTime = (Integer) props.getClass().getMethod("method_190").invoke(props);
                    clearTime = (Integer) props.getClass().getMethod("method_155").invoke(props);
                } catch (Throwable ignored) {}
                return "OK|" + tod + "|" + rain + "|" + thunder + "|" + rainTime + "|" + clearTime;
            }

            if ("SANCTUARY".equals(op)) {
                StringBuilder sb = new StringBuilder("OK|");
                Object overworld = server.getClass().getMethod("method_30002").invoke(server);
                Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
                Object box = boxClass.getConstructor(
                        double.class, double.class, double.class, double.class, double.class, double.class)
                        .newInstance(-215.0, 58.0, 150.0, -135.0, 80.0, 215.0);
                Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                List<?> ents = (List<?>) overworld.getClass()
                        .getMethod("method_8335", entClass, boxClass)
                        .invoke(overworld, null, box);
                java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                for (Object e : ents) {
                    double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                    double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                    String pen = (ex > -175) ? ((ez < 180) ? "NE" : "SE") : ((ez < 180) ? "NW" : "SW");
                    String key = pen + ":" + e.getClass().getSimpleName();
                    counts.put(key, counts.getOrDefault(key, 0) + 1);
                }
                sb.append(counts.toString());
                return sb.toString();
            }

            return "ERR|UNKNOWN_OP";
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }
}
