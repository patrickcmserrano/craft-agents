package com.craft.agents.nav;

import com.craft.agents.api.CollisionType;
import com.craft.agents.api.VoxelAccessor;

import java.util.HashSet;
import java.util.Set;

/**
 * Capacidades de locomoção, custos e restrições cinemáticas do agente sintético,
 * portadas e expandidas do modelo do mineflayer-pathfinder (movements.js) para a JVM.
 */
public final class MovementCapabilities {

    // Custos de Movimentação
    public double walkCost = 1.0;
    public double diagonalCost = Math.sqrt(2.0);
    public double jumpUpCost = 2.0;
    public double portalTraverseCost = 2.5;
    public double swimCost = 2.2;

    // Limites Físicos e Geométricos
    public int maxDropDown = 3;
    public int requiredHeadClearance = 2; // Altura mínima livre na posição de destino
    public int jumpHeadClearance = 3;     // Altura mínima livre na origem para permitir parábola de salto
    public double maxStepHeight = 1.0;
    public boolean allowParkour = false;
    public boolean allowPortals = true;
    public boolean allowClimbing = true;

    // Cinemática Acoplada de Escolta / Guia (Leash)
    public boolean isEscorting = false;
    public boolean escortLeashed = false;
    public String escortSpecies = null;
    public double escortSpeed = 0.165;      // 3.3 m/s (conducao agil e segura com folga de amortecimento)
    public double cornerEasingAngle = 30.0; // Ângulo mínimo para acionar micro-pausa em curvas

    // Lista de Blocos onde NÃO é permitido caminhar ou apoiar degrau (Mineflayer blocksCantWalkOn)
    public static final Set<String> BLOCKS_CANT_WALK_ON = Set.of(
        // Cercas (1.5 de altura de colisão na Mojang)
        "minecraft:oak_fence", "minecraft:spruce_fence", "minecraft:birch_fence",
        "minecraft:jungle_fence", "minecraft:acacia_fence", "minecraft:dark_oak_fence",
        "minecraft:mangrove_fence", "minecraft:cherry_fence", "minecraft:bamboo_fence",
        "minecraft:nether_brick_fence", "minecraft:warped_fence", "minecraft:crimson_fence",

        // Muros (1.5 de altura de colisão na Mojang)
        "minecraft:cobblestone_wall", "minecraft:mossy_cobblestone_wall", "minecraft:stone_brick_wall",
        "minecraft:mossy_stone_brick_wall", "minecraft:granite_wall", "minecraft:diorite_wall",
        "minecraft:andesite_wall", "minecraft:sandstone_wall", "minecraft:red_sandstone_wall",
        "minecraft:brick_wall", "minecraft:prismarine_wall", "minecraft:nether_brick_wall",
        "minecraft:red_nether_brick_wall", "minecraft:end_stone_brick_wall", "minecraft:blackstone_wall",
        "minecraft:polished_blackstone_wall", "minecraft:polished_blackstone_brick_wall",
        "minecraft:deepslate_tile_wall", "minecraft:deepslate_brick_wall", "minecraft:cobbled_deepslate_wall",
        "minecraft:polished_deepslate_wall", "minecraft:mud_brick_wall",

        // Objetos Decorativos de Piso Estreito ou Pequena Altura (não formam piso transitável)
        "minecraft:lantern", "minecraft:soul_lantern", "minecraft:bell", "minecraft:flower_pot",
        "minecraft:brewing_stand", "minecraft:cauldron", "minecraft:water_cauldron", "minecraft:lava_cauldron",
        "minecraft:conduit", "minecraft:end_rod", "minecraft:lightning_rod", "minecraft:chain",
        "minecraft:iron_bars", "minecraft:pointed_dripstone", "minecraft:bamboo"
    );

    // Blocos com Risco de Dano ou Penalidade Crítica (Mineflayer blocksToAvoid)
    public static final Set<String> BLOCKS_TO_AVOID = Set.of(
        "minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:campfire",
        "minecraft:soul_campfire", "minecraft:sweet_berry_bush", "minecraft:cactus",
        "minecraft:wither_rose", "minecraft:magma_block", "minecraft:tripwire",
        "minecraft:cobweb"
    );

    public MovementCapabilities() {}

    public static MovementCapabilities defaults() {
        return new MovementCapabilities();
    }

    public static MovementCapabilities escortDefaults(String species) {
        MovementCapabilities mc = new MovementCapabilities();
        mc.isEscorting = true;
        mc.escortSpecies = species;
        mc.allowParkour = false;
        mc.maxDropDown = 2; // Quedas menores para animais acompanharem sem dano
        return mc;
    }

    public static MovementCapabilities fromCosts(MovementCosts costs) {
        if (costs == null) return defaults();
        MovementCapabilities mc = new MovementCapabilities();
        mc.walkCost = costs.walkCost();
        mc.diagonalCost = costs.diagonalCost();
        mc.jumpUpCost = costs.jumpUpCost();
        mc.portalTraverseCost = costs.portalTraverseCost();
        mc.swimCost = costs.swimCost();
        mc.maxDropDown = costs.maxDropDown();
        mc.allowParkour = costs.allowParkour();
        mc.allowPortals = costs.allowPortals();
        return mc;
    }

    public MovementCosts toCosts() {
        return new MovementCosts(
            walkCost, diagonalCost, jumpUpCost, portalTraverseCost,
            swimCost, maxDropDown, allowParkour, allowPortals
        );
    }

    /**
     * Verifica se o bloco na coordenada pode servir de piso estável para o agente pisar.
     */
    public boolean canStandOn(VoxelAccessor world, int x, int y, int z) {
        CollisionType col = world.getCollisionType(x, y, z);
        if (col != CollisionType.SOLID && col != CollisionType.PORTAL) {
            return false;
        }
        String id = world.getBlockId(x, y, z);
        if (id == null) return true;

        if (BLOCKS_CANT_WALK_ON.contains(id)) return false;
        if (BLOCKS_TO_AVOID.contains(id)) return false;

        // Regras genéricas por sufixo/padrão
        if (id.endsWith("_wall") || id.contains("wall_")) return false;
        if (id.contains("fence") && !id.contains("gate")) return false;
        if (id.contains("pane") || id.contains("bars")) return false;
        if (id.contains("banner") || id.contains("flower_pot")) return false;

        // Se a altura do piso for > 1.0 (ex: cercas com 1.5), não pode ser usado como piso normal
        double fh = world.getFloorHeight(x, y, z);
        double relHeight = (fh > 2.0 || fh < 0.0) ? (fh - y) : fh;
        if (relHeight > 1.05) return false;

        return true;
    }

    /**
     * Verifica se a coordenada contém perigo iminente.
     */
    public boolean isHazard(VoxelAccessor world, int x, int y, int z) {
        if (world.isHazard(x, y, z)) return true;
        String id = world.getBlockId(x, y, z);
        return id != null && BLOCKS_TO_AVOID.contains(id);
    }
}
