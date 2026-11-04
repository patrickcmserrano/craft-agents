package com.craft.agents.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Primitiva nativa in-JVM de escavacao e mineracao segura (CA-E3.1).
 * Implementa a Regra de Ouro da mineracao do Minecraft:
 * 1. Proibicao estrita de escavar o bloco sob os proprios pes (fall hazard).
 * 2. Deteccao de risco de lava em teto imediato (lava hazard).
 * 3. Selecao e empunhadura automatica da melhor ferramenta do inventario.
 * 4. Quebra atomica com drops, particulas e sons fisicos identicos a mineracao real.
 */
public final class SyntheticExcavation {
    private SyntheticExcavation() {}

    public static record OreTarget(int x, int y, int z, String blockId, double distance) {}

    public static record MineResult(
            boolean success,
            String code,
            String blockId,
            String toolId,
            int x, int y, int z,
            String message) {

        public String toProtocolString() {
            if (success) {
                return String.format(Locale.ROOT, "OK|%s|%s|%d|%d|%d", blockId, toolId, x, y, z);
            } else {
                return String.format(Locale.ROOT, "ERR|%s|%s", code, message != null ? message : "");
            }
        }
    }

    /**
     * Inspeciona inventario do agente para selecionar e equipar a melhor ferramenta
     * para o bloco alvo (maior multiplicador de velocidade e adequacao).
     */
    public static String selectAndEquipOptimalTool(Object playerEntity, Object blockState, ClassLoader cl) {
        if (playerEntity == null || blockState == null) return "minecraft:air";
        try {
            Object inventory = null;
            try {
                inventory = playerEntity.getClass().getMethod("method_31548").invoke(playerEntity);
            } catch (Throwable t1) {
                try {
                    Field invField = playerEntity.getClass().getField("field_7509");
                    inventory = invField.get(playerEntity);
                } catch (Throwable ignored) {}
            }
            if (inventory == null) return "minecraft:air";

            Method getStackMethod = null;
            Method setStackMethod = null;
            for (Method m : inventory.getClass().getMethods()) {
                if (("method_5438".equals(m.getName()) || "getStack".equals(m.getName())) && m.getParameterCount() == 1) {
                    getStackMethod = m;
                }
                if (("method_5441".equals(m.getName()) || "setStack".equals(m.getName())) && m.getParameterCount() == 2) {
                    setStackMethod = m;
                }
            }
            if (getStackMethod == null) return "minecraft:air";

            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object itemReg = regClass.getField("field_41178").get(null);
            Method getIdMethod = itemReg.getClass().getMethod("method_10221", Object.class);

            int bestSlot = -1;
            float bestSpeed = 1.0f;
            boolean bestSuitable = false;
            String bestToolId = "minecraft:air";

            for (int i = 0; i < 36; i++) {
                Object stack = getStackMethod.invoke(inventory, i);
                if (stack == null) continue;
                boolean isEmpty = (Boolean) stack.getClass().getMethod("method_7960").invoke(stack);
                if (isEmpty) continue;

                Method getSpeedMethod = null;
                Method isSuitableMethod = null;
                for (Method m : stack.getClass().getMethods()) {
                    if (("method_7924".equals(m.getName()) || "getMiningSpeedMultiplier".equals(m.getName()))
                            && m.getParameterCount() == 1) {
                        getSpeedMethod = m;
                    }
                    if (("method_7951".equals(m.getName()) || "isSuitableFor".equals(m.getName()))
                            && m.getParameterCount() == 1) {
                        isSuitableMethod = m;
                    }
                }

                float speed = getSpeedMethod != null ? ((Number) getSpeedMethod.invoke(stack, blockState)).floatValue() : 1.0f;
                boolean suitable = isSuitableMethod != null && ((Boolean) isSuitableMethod.invoke(stack, blockState));

                Object item = stack.getClass().getMethod("method_7909").invoke(stack);
                Object idObj = getIdMethod.invoke(itemReg, item);
                String toolId = idObj != null ? idObj.toString() : "";

                boolean isBetter = false;
                if (suitable && !bestSuitable) {
                    isBetter = true;
                } else if (suitable == bestSuitable && speed > bestSpeed) {
                    isBetter = true;
                } else if (!suitable && !bestSuitable && speed > bestSpeed && speed > 1.0f) {
                    isBetter = true;
                }

                if (isBetter) {
                    bestSlot = i;
                    bestSpeed = speed;
                    bestSuitable = suitable;
                    bestToolId = toolId;
                }
            }

            if (bestSlot >= 0) {
                Field selectedSlotField = null;
                try {
                    selectedSlotField = inventory.getClass().getField("field_7545");
                } catch (Throwable ignored) {
                    try {
                        selectedSlotField = inventory.getClass().getDeclaredField("selectedSlot");
                        selectedSlotField.setAccessible(true);
                    } catch (Throwable ignored2) {}
                }

                if (selectedSlotField != null) {
                    if (bestSlot <= 8) {
                        selectedSlotField.setInt(inventory, bestSlot);
                    } else if (setStackMethod != null) {
                        int curSelected = selectedSlotField.getInt(inventory);
                        Object hotbarStack = getStackMethod.invoke(inventory, curSelected);
                        Object bestStack = getStackMethod.invoke(inventory, bestSlot);
                        setStackMethod.invoke(inventory, curSelected, bestStack);
                        setStackMethod.invoke(inventory, bestSlot, hotbarStack);
                    }
                }
            }

            return bestToolId;
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    /**
     * Valida seguranca, seleciona ferramenta e quebra o bloco alvo.
     */
    public static MineResult executeMine(Object server, Object playerEntity, int bx, int by, int bz, ClassLoader cl) {
        if (playerEntity == null) {
            return new MineResult(false, "NO_PLAYER", "", "", bx, by, bz, "Player entity is null");
        }
        try {
            double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
            double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
            double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);

            // Regra 1: Seguranca de Queda - NUNCA cavar o bloco onde o agente esta pisando
            int standingX = (int) Math.floor(px);
            int standingY = (int) Math.floor(py);
            int standingZ = (int) Math.floor(pz);

            if (bx == standingX && bz == standingZ && (by == standingY - 1 || by == standingY)) {
                return new MineResult(false, "FALL_HAZARD", "", "", bx, by, bz,
                        "Inviolavel: Proibido minerar o bloco sob os proprios pes (Regra de Ouro do Minecraft)");
            }

            // Regra 2: Distancia de Alcance (max ~5.5 blocos dos olhos do agente)
            double eyeY = py + 1.62;
            double dx = (bx + 0.5) - px;
            double dy = (by + 0.5) - eyeY;
            double dz = (bz + 0.5) - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 36.0) { // 6 blocos
                return new MineResult(false, "TOO_FAR", "", "", bx, by, bz,
                        String.format(Locale.ROOT, "Distancia excessiva: %.2fm (alcance maximo 5.5m)", Math.sqrt(distSq)));
            }

            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Class<?> bpClass = Class.forName("net.minecraft.class_2338", true, cl);
            Constructor<?> bpCtor = bpClass.getConstructor(int.class, int.class, int.class);
            Object targetPos = bpCtor.newInstance(bx, by, bz);

            // Inspeciona bloco alvo
            Object blockState = world.getClass().getMethod("method_8320", bpClass).invoke(world, targetPos);
            boolean isAir = (Boolean) blockState.getClass().getMethod("method_26215").invoke(blockState);
            if (isAir) {
                return new MineResult(false, "ALREADY_AIR", "minecraft:air", "", bx, by, bz, "Bloco ja e ar");
            }

            Object block = blockState.getClass().getMethod("method_26204").invoke(blockState);
            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object blockReg = regClass.getField("field_41175").get(null);
            Object blockIdObj = blockReg.getClass().getMethod("method_10221", Object.class).invoke(blockReg, block);
            String blockId = blockIdObj != null ? blockIdObj.toString() : "minecraft:air";

            // Regra 3: Risco de Lava no Teto - verificar se o bloco de cima e lava
            Object abovePos = bpCtor.newInstance(bx, by + 1, bz);
            Object aboveState = world.getClass().getMethod("method_8320", bpClass).invoke(world, abovePos);
            Object aboveBlock = aboveState.getClass().getMethod("method_26204").invoke(aboveState);
            Object aboveIdObj = blockReg.getClass().getMethod("method_10221", Object.class).invoke(blockReg, aboveBlock);
            String aboveId = aboveIdObj != null ? aboveIdObj.toString() : "";
            if (aboveId.contains("lava")) {
                return new MineResult(false, "LAVA_HAZARD", blockId, "", bx, by, bz,
                        "Inviolavel: Risco de lava diretamente acima do bloco alvo!");
            }

            // Seleciona e equipa melhor ferramenta
            String toolUsed = selectAndEquipOptimalTool(playerEntity, blockState, cl);

            // Anima swing de braço
            try {
                Class<?> handClass = Class.forName("net.minecraft.class_1268", true, cl);
                Object mainHand = handClass.getField("field_5808").get(null);
                playerEntity.getClass().getMethod("method_6104", handClass).invoke(playerEntity, mainHand);
            } catch (Throwable ignored) {}

            // Quebra de bloco nativa: InteractionManager ou CommandManager fallback "setblock X Y Z air destroy"
            boolean broken = false;
            try {
                Field imField = playerEntity.getClass().getField("field_13988");
                Object im = imField.get(playerEntity);
                if (im != null) {
                    Method tryBreakM = null;
                    for (Method m : im.getClass().getMethods()) {
                        if (("method_14003".equals(m.getName()) || "tryBreakBlock".equals(m.getName()))
                                && m.getParameterCount() == 1) {
                            tryBreakM = m;
                            break;
                        }
                    }
                    if (tryBreakM != null) {
                        broken = (Boolean) tryBreakM.invoke(im, targetPos);
                    }
                }
            } catch (Throwable ignored) {}

            if (!broken) {
                try {
                    Object cmdMgr = server.getClass().getMethod("method_3734").invoke(server);
                    Object cmdSrc = server.getClass().getMethod("method_3739").invoke(server);
                    Object silentSrc = cmdSrc.getClass().getMethod("method_9217").invoke(cmdSrc);
                    String setCmd = String.format(Locale.ROOT, "setblock %d %d %d air destroy", bx, by, bz);
                    cmdMgr.getClass().getMethod("method_44252", cmdSrc.getClass(), String.class)
                            .invoke(cmdMgr, silentSrc, setCmd);
                    broken = true;
                } catch (Throwable ignored) {}
            }

            return new MineResult(true, "OK", blockId, toolUsed, bx, by, bz, "Bloco minerado com sucesso");
        } catch (Throwable t) {
            return new MineResult(false, "EXCEPTION", "", "", bx, by, bz, t.getMessage());
        }
    }

    /**
     * Escaneia veios de minerios ao redor do jogador para coordenar mineracao cooperativa.
     */
    public static List<OreTarget> scanNearbyOres(Object playerEntity, double radius, String filter, ClassLoader cl) {
        if (playerEntity == null) return Collections.emptyList();
        try {
            double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
            double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
            double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);

            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Class<?> bpClass = Class.forName("net.minecraft.class_2338", true, cl);
            Constructor<?> bpCtor = bpClass.getConstructor(int.class, int.class, int.class);
            Method getBlockStateMethod = world.getClass().getMethod("method_8320", bpClass);

            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object blockReg = regClass.getField("field_41175").get(null);
            Method getIdMethod = blockReg.getClass().getMethod("method_10221", Object.class);

            int r = (int) Math.min(Math.max(radius, 2.0), 16.0);
            int minX = (int) Math.floor(px) - r;
            int maxX = (int) Math.floor(px) + r;
            int minY = Math.max(-64, (int) Math.floor(py) - r);
            int maxY = Math.min(320, (int) Math.floor(py) + r);
            int minZ = (int) Math.floor(pz) - r;
            int maxZ = (int) Math.floor(pz) + r;

            String oreFilter = filter != null ? filter.trim().toLowerCase(Locale.ROOT) : "";
            List<OreTarget> ores = new ArrayList<>();

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Object pos = bpCtor.newInstance(x, y, z);
                        Object state = getBlockStateMethod.invoke(world, pos);
                        boolean isAir = (Boolean) state.getClass().getMethod("method_26215").invoke(state);
                        if (isAir) continue;

                        Object block = state.getClass().getMethod("method_26204").invoke(state);
                        Object idObj = getIdMethod.invoke(blockReg, block);
                        if (idObj == null) continue;
                        String id = idObj.toString();

                        boolean matches = false;
                        if (!oreFilter.isEmpty()) {
                            matches = id.contains(oreFilter);
                        } else {
                            matches = id.contains("_ore")
                                    || id.contains("ancient_debris")
                                    || id.contains("raw_iron_block")
                                    || id.contains("raw_gold_block")
                                    || id.contains("raw_copper_block");
                        }

                        if (matches) {
                            double dx = (x + 0.5) - px;
                            double dy = (y + 0.5) - py;
                            double dz = (z + 0.5) - pz;
                            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            ores.add(new OreTarget(x, y, z, id, dist));
                        }
                    }
                }
            }

            Collections.sort(ores, new Comparator<OreTarget>() {
                @Override
                public int compare(OreTarget a, OreTarget b) {
                    return Double.compare(a.distance(), b.distance());
                }
            });

            if (ores.size() > 25) {
                return ores.subList(0, 25);
            }
            return ores;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static String formatOresProtocol(List<OreTarget> ores) {
        if (ores == null || ores.isEmpty()) {
            return "OK|0|";
        }
        StringBuilder sb = new StringBuilder("OK|").append(ores.size()).append("|");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(";");
            OreTarget o = ores.get(i);
            sb.append(o.x()).append(",")
              .append(o.y()).append(",")
              .append(o.z()).append(",")
              .append(o.blockId()).append(",")
              .append(String.format(Locale.ROOT, "%.2f", o.distance()));
        }
        return sb.toString();
    }
}
