package com.craft.agents.api;

import java.util.List;
import java.util.Objects;

/**
 * Resultado imutavel do calculo de caminho 3D (CA-002, CA-024).
 */
public class PathResult {
    public enum Status {
        SUCCESS,
        PARTIAL,
        NO_PATH,
        TIMEOUT
    }

    private final Status status;
    private final List<PathNodeStep> steps;
    private final double totalCost;
    private final int visitedNodes;
    private final long elapsedMicros;

    public PathResult(Status status, List<PathNodeStep> steps, double totalCost, int visitedNodes, long elapsedMicros) {
        this.status = status;
        this.steps = steps != null ? steps : List.of();
        this.totalCost = totalCost;
        this.visitedNodes = visitedNodes;
        this.elapsedMicros = elapsedMicros;
    }

    public Status status() { return status; }
    public List<PathNodeStep> steps() { return steps; }
    public double totalCost() { return totalCost; }
    public int visitedNodes() { return visitedNodes; }
    public long elapsedMicros() { return elapsedMicros; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathResult that)) return false;
        return Double.compare(that.totalCost, totalCost) == 0 &&
               visitedNodes == that.visitedNodes &&
               elapsedMicros == that.elapsedMicros &&
               status == that.status &&
               Objects.equals(steps, that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, steps, totalCost, visitedNodes, elapsedMicros);
    }

    @Override
    public String toString() {
        return "PathResult[status=" + status + ", steps=" + steps + ", totalCost=" + totalCost +
               ", visitedNodes=" + visitedNodes + ", elapsedMicros=" + elapsedMicros + "]";
    }
}
