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

    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentLinkedQueue<String> getChatQueue() {
        Object q = System.getProperties().get("craft.agents.chat.queue");
        if (q instanceof java.util.concurrent.ConcurrentLinkedQueue) {
            return (java.util.concurrent.ConcurrentLinkedQueue<String>) q;
        }
        java.util.concurrent.ConcurrentLinkedQueue<String> newQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
        System.getProperties().put("craft.agents.chat.queue", newQ);
        return newQ;
    }

    public static void hookChatEvents(ClassLoader cl) {
        if (cl == null || "true".equals(System.getProperty("craft.agents.chat.hooked"))) return;
        System.setProperty("craft.agents.chat.hooked", "true");
        try {
            Class<?> smeClass = Class.forName("net.fabricmc.fabric.api.message.v1.ServerMessageEvents", true, cl);
            Field f = smeClass.getField("CHAT_MESSAGE");
            Object chatEvent = f.get(null);
            Class<?> chatIface = Class.forName("net.fabricmc.fabric.api.message.v1.ServerMessageEvents$ChatMessage", true, cl);
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                cl,
                new Class<?>[]{chatIface},
                (p, m, args) -> {
                    if ("onChatMessage".equals(m.getName()) && args != null && args.length >= 2) {
                        try {
                            Object msg = args[0];
                            Object player = args[1];
                            String senderName = "";
                            try {
                                Object gp = player.getClass().getMethod("method_7334").invoke(player);
                                senderName = (String) gp.getClass().getMethod("getName").invoke(gp);
                            } catch (Throwable t1) {
                                Object txt = player.getClass().getMethod("method_5477").invoke(player);
                                senderName = (String) txt.getClass().getMethod("getString").invoke(txt);
                            }
                            String text = "";
                            try {
                                text = (String) msg.getClass().getMethod("method_44862").invoke(msg);
                            } catch (Throwable t2) {
                                text = String.valueOf(msg);
                            }
                            if (senderName != null && text != null && !senderName.isEmpty() && !text.isEmpty()) {
                                getChatQueue().add(senderName + "|" + text);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            );
            Method regMethod = chatEvent.getClass().getMethod("register", Object.class);
            regMethod.invoke(chatEvent, proxy);
            System.out.println("[craft-agents] ✅ Hook de Chat Fabric nativo registrado via ServerMessageEvents!");
        } catch (Throwable t) {
            System.out.println("[craft-agents] ℹ️ Hook ServerMessageEvents (fallback para chat poll/injecao): " + t.getMessage());
        }
    }


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

            hookChatEvents(cl);

            if ("CHAT_POLL".equals(op)) {
                StringBuilder sb = new StringBuilder("OK");
                String item;
                while ((item = getChatQueue().poll()) != null) {
                    sb.append(";;").append(item);
                }
                return sb.toString();
            }


            if ("CHAT_INJECT".equals(op) && parts.length >= 3) {
                String sender = parts[1];
                String text = parts[2];
                getChatQueue().add(sender + "|" + text);
                return "OK";
            }

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
                        boolean inWater = false;
                        try {
                            inWater = (Boolean) player.getClass().getMethod("method_5799").invoke(player);
                        } catch (Throwable t1) {
                            try {
                                inWater = (Boolean) player.getClass().getMethod("method_5869").invoke(player);
                            } catch (Throwable t2) {}
                        }
                        if (!inWater) {
                            try {
                                Object overworld = player.getClass().getMethod("method_51469").invoke(player);
                                Class<?> bpCls = Class.forName("net.minecraft.class_2338", true, cl);
                                Object bp = bpCls.getConstructor(int.class, int.class, int.class).newInstance(
                                        (int) Math.floor(x), (int) Math.floor(y + 0.1), (int) Math.floor(z));
                                Object bState = overworld.getClass().getMethod("method_8320", bpCls).invoke(overworld, bp);
                                String bStr = bState.toString().toLowerCase();
                                if (bStr.contains("water")) {
                                    inWater = true;
                                }
                            } catch (Throwable ignored) {}
                        }

                        if (inWater) {
                            try {
                                player.getClass().getMethod("method_5728", boolean.class).invoke(player, true);
                                player.getClass().getMethod("method_5796", boolean.class).invoke(player, true);
                                Class<?> poseCls = Class.forName("net.minecraft.class_4050", true, cl);
                                Object swimPose = poseCls.getField("field_18079").get(null);
                                player.getClass().getMethod("method_18380", poseCls).invoke(player, swimPose);
                                if (sprint) {
                                    double rad = Math.toRadians(yaw);
                                    double speed = 0.18;
                                    double vx = -Math.sin(rad) * speed;
                                    double vz = Math.cos(rad) * speed;
                                    player.getClass().getMethod("method_18799", double.class, double.class, double.class)
                                            .invoke(player, vx, 0.0, vz);
                                }
                            } catch (Throwable ignored) {}
                        } else {
                            try {
                                player.getClass().getMethod("method_5796", boolean.class).invoke(player, false);
                                if (!sneak) {
                                    Class<?> poseCls = Class.forName("net.minecraft.class_4050", true, cl);
                                    Object standPose = poseCls.getField("field_18076").get(null);
                                    player.getClass().getMethod("method_18380", poseCls).invoke(player, standPose);
                                }
                            } catch (Throwable ignored) {}
                        }
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

            if ("EQUIP_ITEM".equals(op) && parts.length >= 4) {
                String name = parts[1];
                String slot = parts[2];
                String itemId = parts[3];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, name);
                if (player != null) {
                    runOnServerThread(server, () -> {
                        try {
                            Class<?> itemRegClass = Class.forName("net.minecraft.class_7923", true, cl);
                            Object itemReg = itemRegClass.getField("field_41178").get(null);
                            Class<?> idCls = Class.forName("net.minecraft.class_2960", true, cl);
                            Object id = idCls.getMethod("method_60654", String.class).invoke(null, itemId);
                            Object itemObj = itemReg.getClass().getMethod("method_10223", idCls).invoke(itemReg, id);
                            Class<?> itemStackCls = Class.forName("net.minecraft.class_1799", true, cl);
                            Class<?> itemConvertibleCls = Class.forName("net.minecraft.class_1935", true, cl);
                            Object stack = itemStackCls.getConstructor(itemConvertibleCls, int.class).newInstance(itemObj, 1);
                            Class<?> handClass = Class.forName("net.minecraft.class_1268", true, cl);
                            if ("offhand".equalsIgnoreCase(slot)) {
                                Object offHand = handClass.getField("field_5810").get(null);
                                player.getClass().getMethod("method_6122", handClass, itemStackCls).invoke(player, offHand, stack);
                            } else {
                                Object mainHand = handClass.getField("field_5808").get(null);
                                player.getClass().getMethod("method_6122", handClass, itemStackCls).invoke(player, mainHand, stack);
                            }
                        } catch (Throwable t) {
                            System.out.println("[EQUIP_ITEM_ERROR] " + t);
                        }
                    });
                    return "OK";
                }
                return "ERR|NO_PLAYER";
            }

            if ("CMD".equals(op) && parts.length >= 2) {
                String cmd = line.substring(4);
                runOnServerThread(server, () -> {
                    try {
                        Object cmdMgr = server.getClass().getMethod("method_3734").invoke(server);
                        Object cmdSrc = server.getClass().getMethod("method_3739").invoke(server);
                        cmdMgr.getClass().getMethod("method_44252", cmdSrc.getClass(), String.class)
                                .invoke(cmdMgr, cmdSrc, cmd);
                    } catch (Throwable t) {
                        System.out.println("[CMD_ERROR] cmd=" + cmd + " failed: " + t);
                    }
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
                        .newInstance(-240.0, 58.0, 135.0, -120.0, 85.0, 230.0);
                Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);
                List<?> ents = (List<?>) overworld.getClass()
                        .getMethod("method_8335", entClass, boxClass)
                        .invoke(overworld, null, box);
                java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                StringBuilder entDetails = new StringBuilder();
                boolean firstEnt = true;
                for (Object e : ents) {
                    if (e == null) continue;
                    double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                    double ey = (Double) e.getClass().getMethod("method_23318").invoke(e);
                    double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                    String rawId = EntityTargetResolver.getEntityRegistryIdOrName(e, cl);
                    String sName = rawId.contains(":") ? rawId.substring(rawId.indexOf(':') + 1) : rawId;
                    if (sName.startsWith("class_") || sName.isEmpty()) sName = e.getClass().getSimpleName();
                    if (!sName.isEmpty()) {
                        sName = Character.toUpperCase(sName.charAt(0)) + sName.substring(1);
                    }
                    String pen;
                    if (ex >= -171.8 && ex <= -135.0 && ez >= 164.0 && ez <= 178.0) pen = "NE";
                    else if (ex >= -171.8 && ex <= -135.0 && ez >= 183.0 && ez <= 197.5) pen = "SE";
                    else if (ex >= -215.5 && ex <= -178.2 && ez >= 183.0 && ez <= 197.5) pen = "SW";
                    else if (ex >= -215.5 && ex <= -178.2 && ez >= 164.0 && ez <= 178.0) pen = "NW";
                    else pen = "OUT";
                    String key = pen + ":" + sName;
                    if (mobClass.isInstance(e)) {
                        counts.put(key, counts.getOrDefault(key, 0) + 1);
                    }

                    String customName = "";
                    try {
                        Object nameObj = e.getClass().getMethod("method_5477").invoke(e);
                        if (nameObj != null) customName = (String) nameObj.getClass().getMethod("getString").invoke(nameObj);
                    } catch (Throwable ignored) {}
                    boolean leashed = false;
                    try {
                        Method isLeashedMethod = e.getClass().getMethod("method_5931");
                        leashed = (Boolean) isLeashedMethod.invoke(e);
                    } catch (Throwable ignored) {}

                    if (!firstEnt) entDetails.append(";");
                    entDetails.append(sName).append(",")
                              .append(customName).append(",")
                              .append(String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", ex, ey, ez))
                              .append(",").append(leashed);
                    firstEnt = false;
                }
                sb.append(counts.toString()).append("|").append(entDetails.toString());
                return sb.toString();
            }


            if ("BLOCK".equals(op) && parts.length >= 4) {
                int bx = Integer.parseInt(parts[1]);
                int by = Integer.parseInt(parts[2]);
                int bz = Integer.parseInt(parts[3]);
                try {
                    Object overworld = server.getClass().getMethod("method_30002").invoke(server);
                    Class<?> bpCls = Class.forName("net.minecraft.class_2338", true, cl);
                    Object bp = bpCls.getConstructor(int.class, int.class, int.class).newInstance(bx, by, bz);
                    Object bState = overworld.getClass().getMethod("method_8320", bpCls).invoke(overworld, bp);
                    Object block = bState.getClass().getMethod("method_26204").invoke(bState);
                    Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
                    Object blockReg = regClass.getField("field_41175").get(null);
                    Object idObj = blockReg.getClass().getMethod("method_10221", Object.class).invoke(blockReg, block);
                    String id = idObj != null ? idObj.toString() : "minecraft:air";
                    return "OK|" + id + "|" + bState.toString();
                } catch (Throwable t) {
                    return "ERR|" + t.getMessage();
                }
            }

            if ("TOGGLE_PORTAL".equals(op) && parts.length >= 4) {
                int bx = Integer.parseInt(parts[1]);
                int by = Integer.parseInt(parts[2]);
                int bz = Integer.parseInt(parts[3]);
                boolean targetOpen = parts.length < 5 || Boolean.parseBoolean(parts[4]);
                try {
                    Object overworld = server.getClass().getMethod("method_30002").invoke(server);
                    Class<?> bpCls = Class.forName("net.minecraft.class_2338", true, cl);
                    Object cmdMgr = server.getClass().getMethod("method_3756").invoke(server);
                    Object cmdSrc = server.getClass().getMethod("method_3763").invoke(server);
                    Method exec = cmdMgr.getClass().getMethod("method_9249", cmdSrc.getClass(), String.class);

                    int[] ys = new int[]{by, by + 1, by - 1};
                    for (int curY : ys) {
                        Object bp = bpCls.getConstructor(int.class, int.class, int.class).newInstance(bx, curY, bz);
                        Object bState = overworld.getClass().getMethod("method_8320", bpCls).invoke(overworld, bp);
                        String s = bState.toString();
                        if (s.contains("_gate") || s.contains("_door") || s.contains("gate") || s.contains("door")) {
                            int b1 = s.indexOf('{');
                            int b2 = s.indexOf('}');
                            if (b1 >= 0 && b2 > b1) {
                                String blockId = s.substring(b1 + 1, b2);
                                String props = s.substring(b2 + 1);
                                if (props.startsWith("[") && props.endsWith("]")) {
                                    String newProps = props.replace("open=false", "open=" + targetOpen)
                                                           .replace("open=true", "open=" + targetOpen);
                                    String cmd = "setblock " + bx + " " + curY + " " + bz + " " + blockId + newProps;
                                    runOnServerThread(server, () -> {
                                        try {
                                            exec.invoke(cmdMgr, cmdSrc, cmd);
                                        } catch (Throwable ignored) {}
                                    });
                                }
                            }
                        }
                    }
                    String sound = targetOpen ? "minecraft:block.fence_gate.open" : "minecraft:block.fence_gate.close";
                    runOnServerThread(server, () -> {
                        try {
                            exec.invoke(cmdMgr, cmdSrc, "playsound " + sound + " block @a " + bx + " " + by + " " + bz + " 1.0 1.0");
                        } catch (Throwable ignored) {}
                    });
                    return "OK";
                } catch (Throwable t) {
                    return "ERR|" + t.getMessage();
                }
            }

            if ("PATH".equals(op) && parts.length >= 3) {
                String[] from = parts[1].split(",");
                String[] to = parts[2].split(",");
                double radius = parts.length >= 4 ? Double.parseDouble(parts[3]) : 1.5;
                int fx = (int) Math.floor(Double.parseDouble(from[0]));
                int fy = (int) Math.floor(Double.parseDouble(from[1]));
                int fz = (int) Math.floor(Double.parseDouble(from[2]));
                int tx = (int) Math.floor(Double.parseDouble(to[0]));
                int ty = (int) Math.floor(Double.parseDouble(to[1]));
                int tz = (int) Math.floor(Double.parseDouble(to[2]));
                try {
                    java.net.URLClassLoader pathLoader = new java.net.URLClassLoader(
                            new java.net.URL[]{ new java.io.File("/home/pace/dev/craft-agents/target/classes/").toURI().toURL() },
                            cl
                    );
                    Class<?> voxCls = pathLoader.loadClass("com.craft.agents.fabric.LiveWorldVoxelAccessor");
                    Object overworld = server.getClass().getMethod("method_30002").invoke(server);
                    Object liveVox = voxCls.getConstructor(Object.class, ClassLoader.class).newInstance(overworld, cl);

                    Class<?> solverCls = pathLoader.loadClass("com.craft.agents.nav.PathSolver3D");
                    Object solver = solverCls.getConstructor().newInstance();

                    Class<?> costsCls = pathLoader.loadClass("com.craft.agents.nav.MovementCosts");
                    Object defaultCosts = costsCls.getMethod("defaults").invoke(null);

                    Class<?> voxInterface = pathLoader.loadClass("com.craft.agents.api.VoxelAccessor");
                    Method solveMethod = solverCls.getMethod("solveToRadius",
                            voxInterface, int.class, int.class, int.class, int.class, int.class, int.class, double.class, costsCls, int.class);

                    Object res = solveMethod.invoke(solver, liveVox, fx, fy, fz, tx, ty, tz, radius, defaultCosts, 3000);
                    Object status = res.getClass().getMethod("status").invoke(res);
                    if ("SUCCESS".equals(status.toString())) {
                        List<?> steps = (List<?>) res.getClass().getMethod("steps").invoke(res);
                        StringBuilder sb = new StringBuilder("OK|");
                        boolean first = true;
                        for (Object s : steps) {
                            if (!first) sb.append(";");
                            first = false;
                            int sx = (Integer) s.getClass().getMethod("x").invoke(s);
                            int sy = (Integer) s.getClass().getMethod("y").invoke(s);
                            int sz = (Integer) s.getClass().getMethod("z").invoke(s);
                            Object act = s.getClass().getMethod("action").invoke(s);
                            sb.append(sx).append(",").append(sy).append(",").append(sz).append(",").append(act.toString());
                        }
                        return sb.toString();
                    } else {
                        return "ERR|NO_PATH";
                    }
                } catch (Throwable t) {
                    return "ERR|" + t.getMessage();
                }
            }

            if ("LEASH".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                String targetType = parts.length >= 3 ? parts[2].trim().toLowerCase() : "";
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    try {
                        Object world = player.getClass().getMethod("method_51469").invoke(player);
                        double px = (Double) player.getClass().getMethod("method_23317").invoke(player);
                        double py = (Double) player.getClass().getMethod("method_23318").invoke(player);
                        double pz = (Double) player.getClass().getMethod("method_23321").invoke(player);
                        Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
                        Object box = boxClass.getConstructor(
                                double.class, double.class, double.class, double.class, double.class, double.class)
                                .newInstance(px - 10.0, py - 4.0, pz - 10.0, px + 10.0, py + 4.0, pz + 10.0);
                        Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);
                        Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                        List<?> ents = (List<?>) world.getClass()
                                .getMethod("method_8335", entClass, boxClass)
                                .invoke(world, null, box);
                        Object closest = null;
                        double bestDist = Double.MAX_VALUE;
                        for (Object e : ents) {
                            if (mobClass.isInstance(e)) {
                                if (!EntityTargetResolver.entityMatchesTarget(e, targetType, cl)) {
                                    continue;
                                }
                                double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                                double ey = (Double) e.getClass().getMethod("method_23318").invoke(e);
                                double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                                double d = (ex - px)*(ex - px) + (ey - py)*(ey - py) + (ez - pz)*(ez - pz);
                                if (d < bestDist) {
                                    bestDist = d;
                                    closest = e;
                                }
                            }
                        }
                        if (closest != null) {
                            if (bestDist > 20.25) { // 4.5 blocks max reach for attaching lead
                                return String.format(java.util.Locale.ROOT, "ERR|TOO_FAR|%.2f", Math.sqrt(bestDist));
                            }
                            Method attachM = null;
                            for (Method m : closest.getClass().getMethods()) {
                                if (("method_60964".equals(m.getName()) || "attachLeash".equals(m.getName()))
                                        && m.getParameterCount() == 2) {
                                    attachM = m;
                                    break;
                                }
                            }
                            if (attachM == null) {
                                for (Method m : closest.getClass().getMethods()) {
                                    if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == boolean.class
                                            && entClass.isAssignableFrom(m.getParameterTypes()[0])) {
                                        attachM = m;
                                        break;
                                    }
                                }
                            }
                            if (attachM != null) {
                                attachM.invoke(closest, player, true);
                                return "OK";
                            }
                            return "ERR|NO_ATTACH_METHOD";
                        }
                        return "ERR|NOT_FOUND";
                    } catch (Throwable t) {
                        return "ERR|" + t.getMessage();
                    }
                }
                return "ERR|NO_PLAYER";
            }

            if ("UNLEASH".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    try {
                        Object world = player.getClass().getMethod("method_51469").invoke(player);
                        double px = (Double) player.getClass().getMethod("method_23317").invoke(player);
                        double py = (Double) player.getClass().getMethod("method_23318").invoke(player);
                        double pz = (Double) player.getClass().getMethod("method_23321").invoke(player);
                        Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
                        Object box = boxClass.getConstructor(
                                double.class, double.class, double.class, double.class, double.class, double.class)
                                .newInstance(px - 16.0, py - 6.0, pz - 16.0, px + 16.0, py + 6.0, pz + 16.0);
                        Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);
                        Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                        List<?> ents = (List<?>) world.getClass()
                                .getMethod("method_8335", entClass, boxClass)
                                .invoke(world, null, box);
                        for (Object e : ents) {
                            if (mobClass.isInstance(e)) {
                                Method detachM = null;
                                for (Method m : e.getClass().getMethods()) {
                                    if (("method_5932".equals(m.getName()) || "detachLeash".equals(m.getName()))
                                            && m.getParameterCount() == 2) {
                                        detachM = m;
                                        break;
                                    }
                                }
                                if (detachM != null) {
                                    detachM.invoke(e, true, false);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                return "OK";
            }

            if ("LEASH_INSPECT".equals(op)) {
                Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);
                Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                StringBuilder sb = new StringBuilder("METHODS|");
                for (Method m : mobClass.getMethods()) {
                    if (m.getParameterCount() == 0 && entClass.isAssignableFrom(m.getReturnType())) {
                        sb.append(m.getName()).append(":").append(m.getReturnType().getSimpleName()).append(";");
                    }
                }
                return sb.toString();
            }

            if ("LEASH_STATUS".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    try {
                        Object world = player.getClass().getMethod("method_51469").invoke(player);
                        double px = (Double) player.getClass().getMethod("method_23317").invoke(player);
                        double py = (Double) player.getClass().getMethod("method_23318").invoke(player);
                        double pz = (Double) player.getClass().getMethod("method_23321").invoke(player);
                        Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
                        Object box = boxClass.getConstructor(
                                double.class, double.class, double.class, double.class, double.class, double.class)
                                .newInstance(px - 24.0, py - 8.0, pz - 24.0, px + 24.0, py + 8.0, pz + 24.0);
                        Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);
                        Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                        List<?> ents = (List<?>) world.getClass()
                                .getMethod("method_8335", entClass, boxClass)
                                .invoke(world, null, box);

                        Object leashedMob = null;
                        double leashedDist = Double.MAX_VALUE;
                        for (Object e : ents) {
                            if (mobClass.isInstance(e)) {
                                boolean isLeashed = false;
                                try {
                                    Method m = e.getClass().getMethod("method_5931");
                                    isLeashed = (Boolean) m.invoke(e);
                                } catch (Throwable ignored) {
                                    try {
                                        Method m = e.getClass().getMethod("isLeashed");
                                        isLeashed = (Boolean) m.invoke(e);
                                    } catch (Throwable ignored2) {}
                                }
                                if (!isLeashed) continue;

                                Object holder = null;
                                try {
                                    Method m = e.getClass().getMethod("method_60952");
                                    holder = m.invoke(e);
                                } catch (Throwable ignored) {
                                    try {
                                        Method m = e.getClass().getMethod("getLeashHolder");
                                        holder = m.invoke(e);
                                    } catch (Throwable ignored2) {}
                                }
                                if (holder != null && holder == player) {
                                    double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                                    double ey = (Double) e.getClass().getMethod("method_23318").invoke(e);
                                    double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                                    double d = Math.sqrt((ex - px)*(ex - px) + (ey - py)*(ey - py) + (ez - pz)*(ez - pz));
                                    leashedMob = e;
                                    leashedDist = d;
                                    break;
                                }
                            }
                        }

                        if (leashedMob != null) {
                            String sName = leashedMob.getClass().getSimpleName();
                            try {
                                Object typeObj = leashedMob.getClass().getMethod("method_5864").invoke(leashedMob);
                                Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
                                Object entReg = regClass.getField("field_41177").get(null);
                                Object idObj = entReg.getClass().getMethod("method_10221", Object.class).invoke(entReg, typeObj);
                                if (idObj != null) sName = idObj.toString();
                            } catch (Throwable ignored) {}

                            double ex = (Double) leashedMob.getClass().getMethod("method_23317").invoke(leashedMob);
                            double ey = (Double) leashedMob.getClass().getMethod("method_23318").invoke(leashedMob);
                            double ez = (Double) leashedMob.getClass().getMethod("method_23321").invoke(leashedMob);
                            int entityId = 0;
                            try {
                                entityId = (Integer) leashedMob.getClass().getMethod("method_5628").invoke(leashedMob);
                            } catch (Throwable ignored) {
                                try {
                                    entityId = (Integer) leashedMob.getClass().getMethod("getId").invoke(leashedMob);
                                } catch (Throwable ignored2) {}
                            }
                            return String.format(java.util.Locale.ROOT, "LEASHED|%d|%s|%.2f|%.2f,%.2f,%.2f",
                                    entityId, sName, leashedDist, ex, ey, ez);
                        }
                        return "UNLEASHED";
                    } catch (Throwable t) {
                        return "ERR|" + t.getMessage();
                    }
                }
                return "ERR|NO_PLAYER";
            }

            if ("LOCATE_MOB".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                String targetType = parts.length >= 3 ? parts[2].trim().toLowerCase() : "";
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    try {
                        Object world = player.getClass().getMethod("method_51469").invoke(player);
                        double px = (Double) player.getClass().getMethod("method_23317").invoke(player);
                        double py = (Double) player.getClass().getMethod("method_23318").invoke(player);
                        double pz = (Double) player.getClass().getMethod("method_23321").invoke(player);
                        Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
                        Object box = boxClass.getConstructor(
                                double.class, double.class, double.class, double.class, double.class, double.class)
                                .newInstance(px - 64.0, py - 16.0, pz - 64.0, px + 64.0, py + 16.0, pz + 64.0);
                        Class<?> mobClass = Class.forName("net.minecraft.class_1308", true, cl);
                        Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
                        List<?> ents = (List<?>) world.getClass()
                                .getMethod("method_8335", entClass, boxClass)
                                .invoke(world, null, box);
                        Object closest = null;
                        double bestDist = Double.MAX_VALUE;
                        for (Object e : ents) {
                            if (mobClass.isInstance(e)) {
                                if (!EntityTargetResolver.entityMatchesTarget(e, targetType, cl)) {
                                    continue;
                                }
                                double ex = (Double) e.getClass().getMethod("method_23317").invoke(e);
                                double ey = (Double) e.getClass().getMethod("method_23318").invoke(e);
                                double ez = (Double) e.getClass().getMethod("method_23321").invoke(e);
                                double d = (ex - px)*(ex - px) + (ey - py)*(ey - py) + (ez - pz)*(ez - pz);
                                if (d < bestDist) {
                                    bestDist = d;
                                    closest = e;
                                }
                            }
                        }
                        if (closest != null) {
                            double ex = (Double) closest.getClass().getMethod("method_23317").invoke(closest);
                            double ey = (Double) closest.getClass().getMethod("method_23318").invoke(closest);
                            double ez = (Double) closest.getClass().getMethod("method_23321").invoke(closest);
                            String rawId = EntityTargetResolver.getEntityRegistryIdOrName(closest, cl);
                            return String.format(java.util.Locale.ROOT, "OK|%.2f|%.2f|%.2f|%s", ex, ey, ez, rawId);
                        }
                        return "ERR|NOT_FOUND";
                    } catch (Throwable t) {
                        return "ERR|" + t.getMessage();
                    }
                }
                return "ERR|NO_PLAYER";
            }

            if ("HEALTH_STATUS".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticPhysiology.inspectPlayerHealth(player, cl).toProtocolString();
                }
                return "ERR|NO_PLAYER";
            }

            if ("LOCATE_ITEMS".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                double radius = parts.length >= 3 ? Double.parseDouble(parts[2]) : 16.0;
                String filter = parts.length >= 4 ? parts[3] : "";
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    List<SyntheticItemCollector.DroppedItem> drops =
                            SyntheticItemCollector.scanNearbyDrops(player, radius, filter, cl);
                    return SyntheticItemCollector.formatDropsProtocol(drops);
                }
                return "ERR|NO_PLAYER";
            }

            if ("TARGET_HOSTILE".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                double radius = parts.length >= 3 ? Double.parseDouble(parts[2]) : 16.0;
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticHostileScanner.scanAndFormat(player, radius, cl);
                }
                return "ERR|NO_PLAYER";
            }

            if ("ATTACK_HOSTILE".equals(op) && parts.length >= 3) {
                String pName = parts[1];
                int targetId = Integer.parseInt(parts[2]);
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    Object world = player.getClass().getMethod("method_51469").invoke(player);
                    Method getEntById = null;
                    for (Method m : world.getClass().getMethods()) {
                        if (("method_8469".equals(m.getName()) || "getEntityById".equals(m.getName()))
                                && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                            getEntById = m;
                            break;
                        }
                    }
                    if (getEntById != null) {
                        Object target = getEntById.invoke(world, targetId);
                        if (target != null) {
                            float cd = SyntheticTacticalCombat.getAttackCooldown(player);
                            SyntheticTacticalCombat.runAttackOnServer(server, player, target);
                            return String.format(java.util.Locale.ROOT, "OK|%d|%.2f", targetId, cd);
                        }
                    }
                    return "ERR|TARGET_NOT_FOUND";
                }
                return "ERR|NO_PLAYER";
            }

            if ("REDEFINE_CLASS".equals(op) && parts.length >= 2) {
                String className = parts[1];
                try {
                    Class<?> target = Class.forName(className);
                    java.io.File f = new java.io.File("/home/pace/dev/craft-agents/target/classes/" + className.replace('.', '/') + ".class");
                    if (f.exists() && inst != null && inst.isRedefineClassesSupported()) {
                        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                        inst.redefineClasses(new java.lang.instrument.ClassDefinition(target, b));
                        return "OK|" + className;
                    }
                    return "ERR|FILE_OR_INST_NOT_FOUND";
                } catch (Throwable t) {
                    return "ERR|" + t.getMessage();
                }
            }

            if ("MINE_BLOCK".equals(op) && parts.length >= 5) {
                String pName = parts[1];
                int bx = Integer.parseInt(parts[2]);
                int by = Integer.parseInt(parts[3]);
                int bz = Integer.parseInt(parts[4]);
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticExcavation.executeMine(server, player, bx, by, bz, cl).toProtocolString();
                }
                return "ERR|NO_PLAYER";
            }

            if ("LOCATE_ORES".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                double radius = parts.length >= 3 ? Double.parseDouble(parts[2]) : 8.0;
                String filter = parts.length >= 4 ? parts[3] : "";
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    List<SyntheticExposedOreScanner.ExposedOreTarget> ores = SyntheticExposedOreScanner.scanExposedOres(player, radius, filter, cl);
                    return SyntheticExposedOreScanner.formatOresProtocol(ores);
                }
                return "ERR|NO_PLAYER";
            }

            if ("RAYCAST_BLOCK".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                double maxDist = parts.length >= 3 ? Double.parseDouble(parts[2]) : 5.5;
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticRaycast.raycastBlock(player, maxDist, cl).toProtocolString();
                }
                return "ERR|NO_PLAYER";
            }

            if ("COLLECT_DROPS".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                double radius = parts.length >= 3 ? Double.parseDouble(parts[2]) : 4.0;
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticInventoryManager.collectNearbyDrops(server, player, radius, cl);
                }
                return "ERR|NO_PLAYER";
            }

            if ("INSPECT_INVENTORY".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticInventoryManager.inspectInventory(player, cl);
                }
                return "ERR|NO_PLAYER";
            }

            if ("DROP_ITEMS".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                String filter = parts.length >= 3 ? parts[2] : "";
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticInventoryManager.dropInventoryItems(server, player, filter, cl);
                }
                return "ERR|NO_PLAYER";
            }

            if ("SIT".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                int sx = parts.length >= 5 ? Integer.parseInt(parts[2]) : Integer.MIN_VALUE;
                int sy = parts.length >= 5 ? Integer.parseInt(parts[3]) : Integer.MIN_VALUE;
                int sz = parts.length >= 5 ? Integer.parseInt(parts[4]) : Integer.MIN_VALUE;
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticSeatManager.sit(server, player, sx, sy, sz, cl);
                }
                return "ERR|NO_PLAYER";
            }

            if ("STAND".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticSeatManager.stand(server, player, cl);
                }
                return "ERR|NO_PLAYER";
            }

            if ("IS_SITTING".equals(op) && parts.length >= 2) {
                String pName = parts[1];
                Object pm = server.getClass().getMethod("method_3760").invoke(server);
                Object player = pm.getClass().getMethod("method_14566", String.class).invoke(pm, pName);
                if (player != null) {
                    return SyntheticSeatManager.isSitting(player, cl);
                }
                return "ERR|NO_PLAYER";
            }

            return "ERR|UNKNOWN_OP";
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }
}
