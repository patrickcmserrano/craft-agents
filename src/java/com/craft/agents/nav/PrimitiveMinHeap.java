package com.craft.agents.nav;

import java.util.NoSuchElementException;

/**
 * Fila de prioridade binaria sobre arrays primitivos long[] e double[] sem alocacao (CA-021).
 */
public final class PrimitiveMinHeap {
    private long[] elements;
    private double[] fScores;
    private int size;

    public PrimitiveMinHeap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be > 0");
        }
        this.elements = new long[initialCapacity];
        this.fScores = new double[initialCapacity];
        this.size = 0;
    }

    public void push(long packedPos, double fScore) {
        ensureCapacity(size + 1);
        int i = size++;
        elements[i] = packedPos;
        fScores[i] = fScore;
        siftUp(i);
    }

    public long popMin() {
        if (size == 0) {
            throw new NoSuchElementException("Heap is empty");
        }
        long min = elements[0];
        int last = --size;
        elements[0] = elements[last];
        fScores[0] = fScores[last];
        if (size > 1) {
            siftDown(0);
        }
        return min;
    }

    public void updateOrPush(long packedPos, double newFScore) {
        int idx = -1;
        for (int i = 0; i < size; i++) {
            if (elements[i] == packedPos) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            double old = fScores[idx];
            fScores[idx] = newFScore;
            if (newFScore < old) {
                siftUp(idx);
            } else if (newFScore > old) {
                siftDown(idx);
            }
        } else {
            push(packedPos, newFScore);
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        size = 0;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > elements.length) {
            int newCap = Math.max(elements.length << 1, minCapacity);
            long[] newElems = new long[newCap];
            double[] newScores = new double[newCap];
            System.arraycopy(elements, 0, newElems, 0, size);
            System.arraycopy(fScores, 0, newScores, 0, size);
            elements = newElems;
            fScores = newScores;
        }
    }

    private void siftUp(int i) {
        long elem = elements[i];
        double score = fScores[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (score >= fScores[parent]) {
                break;
            }
            elements[i] = elements[parent];
            fScores[i] = fScores[parent];
            i = parent;
        }
        elements[i] = elem;
        fScores[i] = score;
    }

    private void siftDown(int i) {
        long elem = elements[i];
        double score = fScores[i];
        int half = size >>> 1;
        while (i < half) {
            int child = (i << 1) + 1;
            int right = child + 1;
            if (right < size && fScores[right] < fScores[child]) {
                child = right;
            }
            if (score <= fScores[child]) {
                break;
            }
            elements[i] = elements[child];
            fScores[i] = fScores[child];
            i = child;
        }
        elements[i] = elem;
        fScores[i] = score;
    }
}
