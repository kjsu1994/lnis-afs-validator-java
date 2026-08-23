package kr.co.lnis.protocol.codec;

/**
 * 동일 seed에서 같은 datagram이 누락되도록 결정론적 Drop 여부를 계산한다.
 *
 * <p>frame sequence와 중복 copy index를 seed와 혼합하므로 반복 실행에서도 같은 packet이 누락된다.
 * 실제 네트워크에서 임의로 버리는 대신 송신 전에 판단해 Test E 결과를 재현 가능하게 만든다.
 */
public final class AfsDropSimulator {
    private AfsDropSimulator() {}

    /** ratePercent가 0이면 항상 전송하고 100이면 모든 대상 datagram을 누락시킨다. */
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
