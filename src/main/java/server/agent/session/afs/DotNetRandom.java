package server.agent.session.afs;

/** Compatibility implementation used by Random(int seed) in the source WPF test logic. */
/** 기존 WPF 시험과 동일한 오류 위치를 재현하는 .NET 호환 난수 생성기다. */
final class DotNetRandom {
  private final int[] seedArray = new int[56];
  private int inext, inextp = 21;

  DotNetRandom(int seed) {
    int subtraction = seed == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(seed);
    int mj = 161803398 - subtraction;
    seedArray[55] = mj;
    int mk = 1;
    for (int i = 1; i < 55; i++) {
      int ii = (21 * i) % 55;
      seedArray[ii] = mk;
      mk = mj - mk;
      if (mk < 0) mk += Integer.MAX_VALUE;
      mj = seedArray[ii];
    }
    for (int cycle = 1; cycle < 5; cycle++) {
      for (int index = 1; index < 56; index++) {
        seedArray[index] -= seedArray[1 + (index + 30) % 55];
        if (seedArray[index] < 0) {
          seedArray[index] += Integer.MAX_VALUE;
        }
      }
    }
  }

  int next(int min, int max) {
    if (min >= max) throw new IllegalArgumentException();
    return (int) (sample() * (max - min)) + min;
  }

  private double sample() {
    return internalSample() * (1.0 / Integer.MAX_VALUE);
  }

  private int internalSample() {
    int locInext = inext + 1;
    if (locInext >= 56) locInext = 1;
    int locInextp = inextp + 1;
    if (locInextp >= 56) locInextp = 1;
    int ret = seedArray[locInext] - seedArray[locInextp];
    if (ret == Integer.MAX_VALUE) ret--;
    if (ret < 0) ret += Integer.MAX_VALUE;
    seedArray[locInext] = ret;
    inext = locInext;
    inextp = locInextp;
    return ret;
  }
}
