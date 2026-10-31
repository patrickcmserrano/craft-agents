package com.craft.agents.nav;

import java.util.Arrays;

/**
 * Conjunto hash aberto de primitivos long para closed-set do A* 3D (CA-021).
 * Implementado com enderecamento aberto e linear probing sem alocacao de Long boxed.
 */
public final class LongHashSet {
    private static final float LOAD_FACTOR = 0.70f;

    private long[] table;
    private boolean[] occupied;
    private int mask;
    private int size;
    private int threshold;

    public LongHashSet(int expectedElements) {
        if (expectedElements <= 0) {
            throw new IllegalArgumentException("expectedElements must be > 0");
        }
        int cap = nextPowerOfTwo((int) Math.ceil(expectedElements / LOAD_FACTOR));
        if (cap < 16) {
            cap = 16;
        }
        this.table = new long[cap];
        this.occupied = new boolean[cap];
        this.mask = cap - 1;
        this.size = 0;
        this.threshold = (int) (cap * LOAD_FACTOR);
    }

    public boolean add(long value) {
        if (size >= threshold) {
            rehash(table.length << 1);
        }
        int idx = mix(value) & mask;
        while (occupied[idx]) {
            if (table[idx] == value) {
                return false;
            }
            idx = (idx + 1) & mask;
        }
        occupied[idx] = true;
        table[idx] = value;
        size++;
        return true;
    }

    public boolean contains(long value) {
        int idx = mix(value) & mask;
        while (occupied[idx]) {
            if (table[idx] == value) {
                return true;
            }
            idx = (idx + 1) & mask;
        }
        return false;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(occupied, false);
        size = 0;
    }

    private void rehash(int newCapacity) {
        long[] oldTable = table;
        boolean[] oldOcc = occupied;
        int newMask = newCapacity - 1;
        long[] newTable = new long[newCapacity];
        boolean[] newOcc = new boolean[newCapacity];

        for (int i = 0; i < oldTable.length; i++) {
            if (oldOcc[i]) {
                long val = oldTable[i];
                int idx = mix(val) & newMask;
                while (newOcc[idx]) {
                    idx = (idx + 1) & newMask;
                }
                newOcc[idx] = true;
                newTable[idx] = val;
            }
        }

        this.table = newTable;
        this.occupied = newOcc;
        this.mask = newMask;
        this.threshold = (int) (newCapacity * LOAD_FACTOR);
    }

    private static int mix(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return (int) x;
    }

    private static int nextPowerOfTwo(int n) {
        int highest = Integer.highestOneBit(n);
        return (highest == n) ? n : highest << 1;
    }
}
