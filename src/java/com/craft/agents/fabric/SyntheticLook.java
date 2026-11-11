package com.craft.agents.fabric;

/**
 * Leitura da direcao do olhar de uma entidade (op LOOK, ponte in-server).
 *
 * POR QUE EXISTE: a mira do jogador era derivada do RAYCAST_BLOCK — o primeiro
 * bloco solido na linha do olhar. Isso e fragil por desenho: olhando para o
 * chao (ou sem bloco a frente) o vetor aponta para baixo/lado e o hostil cai
 * fora do cone de 20 graus, mesmo estando na frente do jogador. Medido em
 * 2026-11-11: o raio acertou `minecraft:deepslate` sob os pes e a mira deu
 * 134 graus com cosseno negativo.
 *
 * O LOOK expoe a direcao REAL do olhar (method_5720 getRotationVec, com
 * fallback yaw/pitch), que nao depende de bloco algum. Reusa a mesma rotina
 * do SyntheticRaycast para nao duplicar a leitura de rotacao.
 */
public final class SyntheticLook {
    private SyntheticLook() {}

    public static SyntheticLookResult look(Object playerEntity) {
        if (playerEntity == null) {
            return new SyntheticLookResult(false, 0, 0, 1, 0, 0);
        }
        try {
            double[] dir = SyntheticRaycast.lookDirection(playerEntity);

            double yaw = 0.0, pitch = 0.0;
            try {
                yaw = ((Number) playerEntity.getClass().getMethod("method_36454").invoke(playerEntity)).doubleValue();
                pitch = ((Number) playerEntity.getClass().getMethod("method_36455").invoke(playerEntity)).doubleValue();
            } catch (Throwable ignored) {}

            return SyntheticLookResult.of(dir, yaw, pitch);
        } catch (Throwable t) {
            return new SyntheticLookResult(false, 0, 0, 1, 0, 0);
        }
    }
}
