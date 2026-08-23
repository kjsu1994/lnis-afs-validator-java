package kr.co.lnis.agent.afs;

/** Compatibility implementation used by Random(int seed) in the source WPF test logic. */
final class DotNetRandom {
    private final int[] seedArray = new int[56]; private int inext, inextp = 21;
    DotNetRandom(int seed) {
        int subtraction = seed == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(seed);
        int mj = 161803398 - subtraction; seedArray[55] = mj; int mk = 1;
        for (int i = 1; i < 55; i++) { int ii = (21 * i) % 55; seedArray[ii] = mk; mk = mj - mk; if (mk < 0) mk += Integer.MAX_VALUE; mj = seedArray[ii]; }
        for (int k = 1; k < 5; k++) for (int i = 1; i < 56; i++) { seedArray[i] -= seedArray[1 + (i + 30) % 55]; if (seedArray[i] < 0) seedArray[i] += Integer.MAX_VALUE; }
    }
    int next(int min, int max) { if (min >= max) throw new IllegalArgumentException(); return (int) (sample() * (max - min)) + min; }
    private double sample() { return internalSample() * (1.0 / Integer.MAX_VALUE); }
    private int internalSample() {
        int locInext = inext + 1; if (locInext >= 56) locInext = 1;
        int locInextp = inextp + 1; if (locInextp >= 56) locInextp = 1;
        int ret = seedArray[locInext] - seedArray[locInextp]; if (ret == Integer.MAX_VALUE) ret--; if (ret < 0) ret += Integer.MAX_VALUE;
        seedArray[locInext] = ret; inext = locInext; inextp = locInextp; return ret;
    }
}

