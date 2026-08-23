package kr.co.lnis.common.codec;

public final class AfsDropSimulator {
    private AfsDropSimulator() {}

    public static boolean shouldDrop(long frameSequence, int copyIndex, double ratePercent, int seed) {
        if (ratePercent <= 0) return false;
        if (ratePercent >= 100) return true;
        int value = seed ^ (int) ((frameSequence + 1) * 0x9E3779B9L) ^ ((copyIndex + 1) * 0x85EBCA6B);
        value ^= value >>> 16; value *= 0x7FEB352D;
        value ^= value >>> 15; value *= 0x846CA68B;
        value ^= value >>> 16;
        return Integer.toUnsignedLong(value) / 4294967296.0 * 100.0 < ratePercent;
    }
}

