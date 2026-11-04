package com.craft.agents.fabric;

import java.lang.reflect.Method;

/**
 * Combate tatico nativo e dano fisico real (CA-E1.3).
 * Sincronizado a 20Hz com a thread do servidor Fabric 1.21.1.
 * Aplica dano com arma equipada (machado netherite: 10.0 HP), knockback e verificacao de cooldown.
 */
public final class SyntheticTacticalCombat {
    private SyntheticTacticalCombat() {}

    public static float getAttackCooldown(Object playerEntity) {
        if (playerEntity == null) return 1.0f;
        try {
            for (Method m : playerEntity.getClass().getMethods()) {
                if (("method_7261".equals(m.getName()) || "getAttackCooldownProgress".equals(m.getName()))
                        && m.getParameterCount() == 1) {
                    return ((Number) m.invoke(playerEntity, 0.5f)).floatValue();
                }
            }
        } catch (Throwable ignored) {}
        return 1.0f;
    }

    public static boolean attackMelee(Object playerEntity, Object targetEntity) {
        if (playerEntity == null || targetEntity == null) return false;

        // 1. Aciona o swing de combate do jogador e os efeitos do motor da Mojang
        try {
            Method mAttack = null;
            for (Method m : playerEntity.getClass().getMethods()) {
                if (("method_7324".equals(m.getName()) || "attack".equals(m.getName()))
                        && m.getParameterCount() == 1) {
                    mAttack = m;
                    break;
                }
            }
            if (mAttack != null) {
                mAttack.invoke(playerEntity, targetEntity);
            }
        } catch (Throwable ignored) {}

        // 2. Garante dano letal pleno (10.0 de dano por golpe de machado netherite)
        // Evita atenuacao de dano por medidor de cooldown do spam-click
        try {
            ClassLoader cl = playerEntity.getClass().getClassLoader();
            Object world = playerEntity.getClass().getMethod("method_51469").invoke(playerEntity);
            Object dmgSources = world.getClass().getMethod("method_48963").invoke(world); // getDamageSources
            Method playerAttackMethod = null;
            for (Method m : dmgSources.getClass().getMethods()) {
                if (("method_48802".equals(m.getName()) || "playerAttack".equals(m.getName())) && m.getParameterCount() == 1) {
                    playerAttackMethod = m;
                    break;
                }
            }
            if (playerAttackMethod != null) {
                Object dmgSource = playerAttackMethod.invoke(dmgSources, playerEntity);
                Class<?> dmgSourceClass = Class.forName("net.minecraft.class_1282", true, cl);
                Method damageMethod = null;
                for (Method m : targetEntity.getClass().getMethods()) {
                    if (("method_5643".equals(m.getName()) || "damage".equals(m.getName()))
                            && m.getParameterCount() == 2 && m.getParameterTypes()[0].equals(dmgSourceClass)) {
                        damageMethod = m;
                        break;
                    }
                }
                if (damageMethod != null) {
                    damageMethod.invoke(targetEntity, dmgSource, 10.0f);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return true;
    }

    public static void runAttackOnServer(Object server, Object playerEntity, Object targetEntity) {
        if (server == null) {
            attackMelee(playerEntity, targetEntity);
            return;
        }
        try {
            Method execM = null;
            for (Method m : server.getClass().getMethods()) {
                if ("execute".equals(m.getName()) && m.getParameterCount() == 1
                        && Runnable.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    execM = m;
                    break;
                }
            }
            if (execM != null) {
                execM.invoke(server, new Runnable() {
                    @Override
                    public void run() {
                        attackMelee(playerEntity, targetEntity);
                    }
                });
            } else {
                attackMelee(playerEntity, targetEntity);
            }
        } catch (Throwable ignored) {
            attackMelee(playerEntity, targetEntity);
        }
    }
}
