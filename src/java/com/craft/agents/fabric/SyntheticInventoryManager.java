package com.craft.agents.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gerenciador nativo de inventario e saque (looting) de itens in-JVM (CA-E1.4 / CA-E3.1).
 * Permite que agentes sintéticos (Karlach e Picard) coletem itens caídos no chão (drops de minérios,
 * mobs e ferramentas), guardem no inventário real do ServerPlayerEntity e descarreguem/entreguem
 * aos jogadores humanos quando solicitado.
 */
public final class SyntheticInventoryManager {
    private SyntheticInventoryManager() {}

    public static record InventoryItem(int slot, String itemId, int count) {}

    /**
     * Coleta itens caídos (ItemEntity) próximos ao jogador e insere em seu inventário real.
     * Toca o som nativo de pickup e descarta a entidade absorvida do ServerWorld.
     */
    public static String collectNearbyDrops(Object server, Object playerEntity, double radius, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            double px = (Double) playerEntity.getClass().getMethod("method_23317").invoke(playerEntity);
            double py = (Double) playerEntity.getClass().getMethod("method_23318").invoke(playerEntity);
            double pz = (Double) playerEntity.getClass().getMethod("method_23321").invoke(playerEntity);

            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Class<?> boxClass = Class.forName("net.minecraft.class_238", true, cl);
            Object box = boxClass.getConstructor(
                    double.class, double.class, double.class, double.class, double.class, double.class)
                    .newInstance(px - radius, py - 2.5, pz - radius, px + radius, py + 2.5, pz + radius);

            Class<?> entClass = Class.forName("net.minecraft.class_1297", true, cl);
            Class<?> itemEntClass = Class.forName("net.minecraft.class_1542", true, cl); // ItemEntity
            Class<?> itemStackClass = Class.forName("net.minecraft.class_1799", true, cl);

            List<?> ents = (List<?>) world.getClass()
                    .getMethod("method_8335", entClass, boxClass)
                    .invoke(world, null, box);

            if (ents == null || ents.isEmpty()) {
                return "OK|0|";
            }

            Object inventory = null;
            try {
                inventory = playerEntity.getClass().getMethod("method_31548").invoke(playerEntity);
            } catch (Throwable t) {
                try {
                    inventory = playerEntity.getClass().getMethod("getInventory").invoke(playerEntity);
                } catch (Throwable ignored) {}
            }

            Method insertStackMethod = null;
            if (inventory != null) {
                for (Method m : inventory.getClass().getMethods()) {
                    if (("method_7394".equals(m.getName()) || "method_7399".equals(m.getName()) || "insertStack".equals(m.getName()))
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == itemStackClass) {
                        insertStackMethod = m;
                        break;
                    }
                }
            }
            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object itemReg = regClass.getField("field_41178").get(null);
            Method getIdMethod = itemReg.getClass().getMethod("method_10221", Object.class);

            Map<String, Integer> collected = new LinkedHashMap<>();
            int totalCount = 0;

            for (Object e : ents) {
                if (!itemEntClass.isInstance(e)) continue;

                // Verifica se a entidade ainda esta viva / nao descartada
                boolean isRemoved = false;
                try {
                    isRemoved = (Boolean) e.getClass().getMethod("method_31481").invoke(e); // isRemoved
                } catch (Throwable ignored) {}
                if (isRemoved) continue;

                // Verifica se a entidade tem pickup delay ativo (ex: acabou de ser dropada por Karlach ou player)
                boolean cannotPickup = false;
                try {
                    cannotPickup = (Boolean) e.getClass().getMethod("method_6975").invoke(e); // cannotPickup()
                } catch (Throwable ignored) {
                    try {
                        cannotPickup = (Boolean) e.getClass().getMethod("cannotPickup").invoke(e);
                    } catch (Throwable ignored2) {}
                }
                if (cannotPickup) continue;

                // Não coleta itens dropados por jogadores humanos (ex: troca de itens entre jogadores)
                try {
                    Object thrower = null;
                    try {
                        thrower = e.getClass().getMethod("method_57388").invoke(e); // getThrower()
                    } catch (Throwable ignored) {
                        try {
                            thrower = e.getClass().getMethod("getThrower").invoke(e);
                        } catch (Throwable ignored2) {}
                    }
                    if (thrower != null) {
                        continue;
                    }
                } catch (Throwable ignored) {}

                Object stack = null;
                try {
                    stack = e.getClass().getMethod("method_6983").invoke(e); // getStack
                } catch (Throwable ignored) {
                    try {
                        stack = e.getClass().getMethod("getStack").invoke(e);
                    } catch (Throwable ignored2) {}
                }
                if (stack == null) continue;

                boolean isEmpty = (Boolean) stack.getClass().getMethod("method_7960").invoke(stack); // isEmpty
                if (isEmpty) continue;

                int initialCount = ((Number) stack.getClass().getMethod("method_7947").invoke(stack)).intValue(); // getCount
                Object itemObj = stack.getClass().getMethod("method_7909").invoke(stack); // getItem
                Object idObj = getIdMethod.invoke(itemReg, itemObj);
                String itemId = idObj != null ? idObj.toString() : "minecraft:air";

                boolean inserted = false;
                if (insertStackMethod != null) {
                    try {
                        inserted = (Boolean) insertStackMethod.invoke(inventory, stack);
                    } catch (Throwable ignored) {}
                }

                int remainingCount = 0;
                boolean nowEmpty = (Boolean) stack.getClass().getMethod("method_7960").invoke(stack);
                if (!nowEmpty) {
                    remainingCount = ((Number) stack.getClass().getMethod("method_7947").invoke(stack)).intValue();
                }

                int pickedUp = initialCount - remainingCount;
                if (pickedUp > 0 || inserted) {
                    if (pickedUp == 0) pickedUp = initialCount;
                    totalCount += pickedUp;
                    collected.put(itemId, collected.getOrDefault(itemId, 0) + pickedUp);

                    // Descarta entidade do mundo se consumida
                    if (nowEmpty || pickedUp >= initialCount) {
                        try {
                            e.getClass().getMethod("method_31745").invoke(e); // discard
                        } catch (Throwable ignored) {
                            try {
                                e.getClass().getMethod("discard").invoke(e);
                            } catch (Throwable ignored2) {}
                        }
                    }

                    // Toca som e particulas de pickup via comando no servidor
                    if (server != null) {
                        try {
                            Object cmdMgr = server.getClass().getMethod("method_3756").invoke(server);
                            Object cmdSrc = server.getClass().getMethod("method_3763").invoke(server);
                            Method exec = cmdMgr.getClass().getMethod("method_9249", cmdSrc.getClass(), String.class);
                            String soundCmd = String.format(Locale.ROOT,
                                    "playsound minecraft:entity.item.pickup player @a %.2f %.2f %.2f 0.6 1.2", px, py, pz);
                            exec.invoke(cmdMgr, cmdSrc, soundCmd);
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (totalCount == 0) {
                return "OK|0|";
            }

            StringBuilder sb = new StringBuilder("OK|").append(totalCount).append("|");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : collected.entrySet()) {
                if (!first) sb.append(";");
                sb.append(entry.getKey()).append(",").append(entry.getValue());
                first = false;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    /**
     * Le o item na MAO PRINCIPAL do jogador (slot selecionado da hotbar).
     * Devolve: OK|<id>|<count>|<damage>|<max_damage>
     * `damage`/`max_damage` sao lidos por metodos VERIFICADOS no jar intermediary
     * (server-intermediary.jar, 1.21.1): `method_7919` = getDamage (clampa contra
     * o maximo), `method_7936` = getMaxDamage. Medido por javap, nao por palpite.
     * Usado pelo op HELD_ITEM: o engine ve a durabilidade subir (carga gasta) e
     * dispara a magia.
     */
    public static String heldItem(Object playerEntity, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            Object inventory;
            try {
                inventory = playerEntity.getClass().getMethod("method_31548").invoke(playerEntity);
            } catch (Throwable t) {
                return "ERR|NO_INVENTORY";
            }

            // O stack da MAO vem do getter OFICIAL da PlayerInventory:
            // `method_7391()` = getStackInHand -> `field_7547.get(field_7545)`,
            // com guarda `method_7380(sel)` (selectedSlot < 9). Resolvido por
            // bytecode no server-intermediary.jar em 2026-11-11. Antes disto o
            // op pedia `getStack(int)` e passava o `selected` a mao: o
            // `PlayerInventory` TEM dois `getStack(int)` (method_5438 e
            // method_5441) que indexam a lista de containers `field_7543`, e o
            // op ainda tentava um `method_31549` do PlayerEntity que devolve
            // `class_1656` (PlayerAbilities), nao int — o cast morria no catch e
            // o HELD_ITEM respondia air com a varinha na mao.
            Object stack;
            try {
                stack = inventory.getClass().getMethod("method_7391").invoke(inventory);
            } catch (Throwable t) {
                return "ERR|NO_HAND_GETTER:" + t.getClass().getSimpleName();
            }
            if (stack == null) return "OK|minecraft:air|0|0|0";
            boolean isEmpty = (Boolean) stack.getClass().getMethod("method_7960").invoke(stack);
            if (isEmpty) return "OK|minecraft:air|0|0|0";

            int count = ((Number) stack.getClass().getMethod("method_7947").invoke(stack)).intValue();
            Object itemObj = stack.getClass().getMethod("method_7909").invoke(stack);
            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object itemReg = regClass.getField("field_41178").get(null);
            Method getIdMethod = itemReg.getClass().getMethod("method_10221", Object.class);
            Object idObj = getIdMethod.invoke(itemReg, itemObj);
            String itemId = idObj != null ? idObj.toString() : "minecraft:air";

            int damage = 0, maxDamage = 0;
            try {
                maxDamage = ((Number) stack.getClass().getMethod("method_7936").invoke(stack)).intValue();
            } catch (Throwable ignored) {}
            try {
                damage = ((Number) stack.getClass().getMethod("method_7919").invoke(stack)).intValue();
            } catch (Throwable ignored) {}

            return String.format(Locale.ROOT, "OK|%s|%d|%d|%d",
                    itemId, count, damage, maxDamage);
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    /**
     * Inspeciona o inventário do jogador e retorna os itens armazenados.
     */
    public static String inspectInventory(Object playerEntity, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            Object inventory = null;
            try {
                inventory = playerEntity.getClass().getMethod("method_31548").invoke(playerEntity);
            } catch (Throwable t) {
                try {
                    inventory = playerEntity.getClass().getMethod("getInventory").invoke(playerEntity);
                } catch (Throwable ignored) {}
            }
            if (inventory == null) return "ERR|NO_INVENTORY";

            Method getStackMethod = null;
            for (Method m : inventory.getClass().getMethods()) {
                if (("method_5438".equals(m.getName()) || "getStack".equals(m.getName()))
                        && m.getParameterCount() == 1) {
                    getStackMethod = m;
                    break;
                }
            }
            if (getStackMethod == null) return "ERR|NO_GET_STACK";

            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object itemReg = regClass.getField("field_41178").get(null);
            Method getIdMethod = itemReg.getClass().getMethod("method_10221", Object.class);

            Map<String, Integer> itemTotals = new LinkedHashMap<>();
            int occupiedSlots = 0;

            for (int i = 0; i < 36; i++) {
                Object stack = getStackMethod.invoke(inventory, i);
                if (stack == null) continue;
                boolean isEmpty = (Boolean) stack.getClass().getMethod("method_7960").invoke(stack);
                if (isEmpty) continue;

                int count = ((Number) stack.getClass().getMethod("method_7947").invoke(stack)).intValue();
                Object itemObj = stack.getClass().getMethod("method_7909").invoke(stack);
                Object idObj = getIdMethod.invoke(itemReg, itemObj);
                String itemId = idObj != null ? idObj.toString() : "minecraft:air";

                occupiedSlots++;
                itemTotals.put(itemId, itemTotals.getOrDefault(itemId, 0) + count);
            }

            StringBuilder sb = new StringBuilder("OK|").append(occupiedSlots).append("|");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : itemTotals.entrySet()) {
                if (!first) sb.append(";");
                sb.append(entry.getKey()).append(",").append(entry.getValue());
                first = false;
            }

            return sb.toString();
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    /**
     * Ejeta itens do inventário no mundo (drop/loot sharing).
     */
    public static String dropInventoryItems(Object server, Object playerEntity, String filter, ClassLoader cl) {
        if (playerEntity == null) return "ERR|NO_PLAYER";
        try {
            Object inventory = null;
            try {
                inventory = playerEntity.getClass().getMethod("method_31548").invoke(playerEntity);
            } catch (Throwable t) {
                try {
                    inventory = playerEntity.getClass().getMethod("getInventory").invoke(playerEntity);
                } catch (Throwable ignored) {}
            }
            if (inventory == null) return "ERR|NO_INVENTORY";

            Method getStackMethod = null;
            Method setStackMethod = null;
            Class<?> itemStackClass = Class.forName("net.minecraft.class_1799", true, cl);
            Object emptyStack = itemStackClass.getField("field_8037").get(null); // ItemStack.EMPTY

            for (Method m : inventory.getClass().getMethods()) {
                if (("method_5438".equals(m.getName()) || "getStack".equals(m.getName())) && m.getParameterCount() == 1) {
                    getStackMethod = m;
                }
                if (m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == int.class
                        && m.getParameterTypes()[1] == itemStackClass
                        && m.getReturnType() == void.class) {
                    setStackMethod = m;
                    System.out.println("[FOUND_SET_STACK] " + m.getName());
                }
            }

            Class<?> regClass = Class.forName("net.minecraft.class_7923", true, cl);
            Object itemReg = regClass.getField("field_41178").get(null);
            Method getIdMethod = itemReg.getClass().getMethod("method_10221", Object.class);

            Method dropItemMethod = null;
            for (Method m : playerEntity.getClass().getMethods()) {
                if (("method_7328".equals(m.getName()) || "dropItem".equals(m.getName()))
                        && m.getParameterCount() >= 2
                        && m.getParameterTypes()[0] == itemStackClass) {
                    dropItemMethod = m;
                    break;
                }
            }

            String targetFilter = filter != null ? filter.trim().toLowerCase(Locale.ROOT) : "";
            int droppedCount = 0;
            Map<String, Integer> droppedSummary = new LinkedHashMap<>();

            for (int i = 0; i < 36; i++) {
                Object stack = getStackMethod.invoke(inventory, i);
                if (stack == null) continue;
                boolean isEmpty = (Boolean) stack.getClass().getMethod("method_7960").invoke(stack);
                if (isEmpty) continue;

                Object itemObj = stack.getClass().getMethod("method_7909").invoke(stack);
                Object idObj = getIdMethod.invoke(itemReg, itemObj);
                String itemId = idObj != null ? idObj.toString() : "";

                // Não dropa ferramentas, armas, armaduras ou equipamentos essenciais a menos que solicitado com all/tudo/force_all
                if (!"all".equals(targetFilter) && !"tudo".equals(targetFilter) && !"force_all".equals(targetFilter) && !"gear".equals(targetFilter)) {
                    if (itemId.contains("pickaxe") || itemId.contains("axe") || itemId.contains("sword")
                            || itemId.contains("shovel") || itemId.contains("hoe") || itemId.contains("shield")
                            || itemId.contains("mace") || itemId.contains("bow") || itemId.contains("helmet")
                            || itemId.contains("chestplate") || itemId.contains("leggings") || itemId.contains("boots")
                            || itemId.contains("totem")) {
                        continue;
                    }
                }

                boolean matches = true;
                if (!targetFilter.isEmpty() && !"all".equals(targetFilter) && !"tudo".equals(targetFilter)) {
                    matches = itemId.contains(targetFilter);
                }

                if (matches) {
                    int count = ((Number) stack.getClass().getMethod("method_7947").invoke(stack)).intValue();
                    droppedCount += count;
                    droppedSummary.put(itemId, droppedSummary.getOrDefault(itemId, 0) + count);

                    // Cria cópia do ItemStack antes de esvaziar o slot
                    Object stackCopy = null;
                    try {
                        stackCopy = stack.getClass().getMethod("method_7972").invoke(stack); // copy()
                    } catch (Throwable ignored) {
                        try {
                            stackCopy = stack.getClass().getMethod("copy").invoke(stack);
                        } catch (Throwable ignored2) {
                            stackCopy = stack;
                        }
                    }

                    if (dropItemMethod != null && stackCopy != null) {
                        try {
                            Object itemEntity = null;
                            if (dropItemMethod.getParameterCount() == 2) {
                                itemEntity = dropItemMethod.invoke(playerEntity, stackCopy, false);
                            } else if (dropItemMethod.getParameterCount() == 3) {
                                itemEntity = dropItemMethod.invoke(playerEntity, stackCopy, false, false);
                            }
                            if (itemEntity != null) {
                                try {
                                    itemEntity.getClass().getMethod("method_6977", int.class).invoke(itemEntity, 60); // setPickupDelay(60 ticks = 3s)
                                } catch (Throwable ignored) {
                                    try {
                                        itemEntity.getClass().getMethod("setPickupDelay", int.class).invoke(itemEntity, 60);
                                    } catch (Throwable ignored2) {}
                                }
                            }
                        } catch (Throwable t) {
                            System.out.println("[DROP_ERROR] Erro ao dropar ItemEntity: " + t);
                        }
                    }
                    if (setStackMethod != null) {
                        try {
                            setStackMethod.invoke(inventory, i, emptyStack);
                        } catch (Throwable t) {
                            System.out.println("[DROP_ERROR] Erro ao esvaziar slot " + i + ": " + t);
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder("OK|").append(droppedCount).append("|");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : droppedSummary.entrySet()) {
                if (!first) sb.append(";");
                sb.append(entry.getKey()).append(",").append(entry.getValue());
                first = false;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }
}
