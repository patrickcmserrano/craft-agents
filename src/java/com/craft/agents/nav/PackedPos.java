package com.craft.agents.nav;

/**
 * Codificacao bit-a-bit de (x, y, z) em primitivo long 64-bit zero-allocation (CA-021).
 * Layout compativel com o formato nativo do Minecraft:
 * - Bits 63..38: X (26 bits com sinal, [-30M..+30M])
 * - Bits 37..12: Z (26 bits com sinal, [-30M..+30M])
 * - Bits 11..0:  Y (12 bits com sinal, [-2048..+2047])
 */
public final class PackedPos {
    private PackedPos() {}

    public static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
             | (((long) z & 0x3FFFFFFL) << 12)
             | ((long) y & 0xFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) ((packed << 52) >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) ((packed << 26) >> 38);
    }
}
