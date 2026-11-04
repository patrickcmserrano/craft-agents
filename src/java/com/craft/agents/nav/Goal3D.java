package com.craft.agents.nav;

/**
 * Interface polimórfica de metas espaciais para o PathSolver3D,
 * inspirada no subsistema de Goals do mineflayer-pathfinder.
 */
public interface Goal3D {
    /**
     * Verifica se o nó (x, y, z) atende aos critérios da meta.
     */
    boolean isSatisfied(int x, int y, int z);

    /**
     * Retorna a estimativa heurística (h-score admissível) de (x, y, z) até a meta mais próxima.
     */
    double heuristic(int x, int y, int z);
}
