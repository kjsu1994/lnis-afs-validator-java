package kr.co.lnis.agent.afs;

import kr.co.lnis.agent.nativecodec.NativeAfsCodec;
import kr.co.lnis.common.codec.GrawCodec;
import kr.co.lnis.common.model.LnisModels.TestOptions;
import kr.co.lnis.common.model.LnisModels.TestType;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class AfsFrameBuilder {
    public record Frame(int week, int intervalOfWeek, int timeOfInterval, byte[] payload) {}
    public record Prepared(List<Frame> frames, int injectedFrameCount, long recordCount) {}
    private final NativeAfsCodec codec;
    public AfsFrameBuilder(NativeAfsCodec codec) { this.codec = codec; }

    public Prepared prepare(List<byte[]> records, TestOptions options) {
        if (records.isEmpty()) throw new IllegalArgumentException("capture.graw is empty");
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) blocks.addAll(AfsRawFragmentCodec.fragment(i, records.get(i)));
        int[] time = timeFrom(records); int totalFrames = (blocks.size() + 1) / 2, injected = 0; List<Frame> frames = new ArrayList<>(totalFrames);
        for (int index = 0; index < blocks.size(); index += 2) {
            byte[] second = index + 1 < blocks.size() ? blocks.get(index + 1) : blocks.get(index);
            byte[] encoded = codec.encode(time[2], sb2(time[0], time[1]), AfsRawFragmentCodec.toSbBits(blocks.get(index)), AfsRawFragmentCodec.toSbBits(second));
            int frameIndex = frames.size(); int mode = mode(options.testType(), frameIndex, totalFrames, options.syncDamageInterval());
            if (mode != 0) { inject(encoded, mode, options.errorCount(), options.errorSeed(), frameIndex); injected++; }
            frames.add(new Frame(time[0], time[1], time[2], encoded)); advance(time);
        }
        if (options.testType() == TestType.TEST_D_SYNC_RECOVERY && frames.size() < 2) throw new IllegalArgumentException("Test D requires at least two frames");
        return new Prepared(List.copyOf(frames), injected, records.size());
    }

    private static int mode(TestType type, int index, int total, int interval) {
        if (type == TestType.TEST_B_RANDOM_ERRORS) return 1;
        if (type == TestType.TEST_C_BURST_ERRORS) return 2;
        return type == TestType.TEST_D_SYNC_RECOVERY && index < total - 1 && index % interval == 0 ? 3 : 0;
    }
    private static void inject(byte[] frame, int mode, int count, int seed, int trial) {
        int start = mode == 3 ? 0 : 120, end = mode == 3 ? 68 : 6000;
        if (count < 1 || count > end - start) throw new IllegalArgumentException("Invalid error count");
        DotNetRandom random = new DotNetRandom(seed * 397 ^ trial); Set<Integer> indices = new TreeSet<>();
        if (mode == 2) { int first = random.next(start, end - count + 1); for (int i = 0; i < count; i++) indices.add(first + i); }
        else while (indices.size() < count) indices.add(random.next(start, end));
        for (int bit : indices) frame[bit >>> 3] ^= (byte) (1 << (7 - (bit & 7)));
    }
    private static byte[] sb2(int week, int interval) {
        byte[] bits = new byte[1176]; int state = 0x6D2B79F5 ^ (week << 9) ^ interval;
        for (int i = 0; i < bits.length; i++) { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; bits[i] = (byte) (state & 1); }
        write(bits, 0, 13, week); write(bits, 13, 9, interval); return bits;
    }
    private static void write(byte[] bits, int offset, int length, long value) { for (int i = 0; i < length; i++) bits[offset + i] = (byte) ((value >> (length - i - 1)) & 1); }
    private static int[] timeFrom(List<byte[]> records) {
        for (byte[] record : records) {
            var envelope = GrawCodec.decode(record);
            if (envelope.message() instanceof GrawCodec.ObservationEpoch x) return nextTime(x.week(), x.receiverTowSeconds());
        }
        Instant captured = GrawCodec.decode(records.getFirst()).capturedAt();
        long gpsSeconds = Duration.between(Instant.parse("1980-01-06T00:00:00Z"), captured).getSeconds() + 18;
        return nextTime((int) (gpsSeconds / 604800), gpsSeconds % 604800);
    }
    private static int[] nextTime(int week, double seconds) { int interval = (int) (seconds / 1200), toi = (int) (seconds % 1200) / 12 + 1; if (toi >= 100) { toi = 0; if (++interval >= 504) { interval = 0; week++; } } return new int[]{week, interval, toi}; }
    private static void advance(int[] time) { if (++time[2] < 100) return; time[2] = 0; if (++time[1] < 504) return; time[1] = 0; time[0]++; }
}

