package kr.co.lnis.agent.gnss;

import kr.co.lnis.common.codec.GrawCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UbloxParser {
    private byte[] pending = new byte[0];
    public record UbxFrame(int messageClass, int messageId, byte[] payload) {}

    public synchronized List<UbxFrame> push(byte[] bytes, int length) {
        byte[] joined = Arrays.copyOf(pending, pending.length + length); System.arraycopy(bytes, 0, joined, pending.length, length); pending = joined;
        List<UbxFrame> frames = new ArrayList<>(); int offset = 0;
        while (pending.length - offset >= 8) {
            while (offset + 1 < pending.length && (pending[offset] != (byte) 0xB5 || pending[offset + 1] != 0x62)) offset++;
            if (pending.length - offset < 8) break;
            int payloadLength = (pending[offset + 4] & 0xff) | ((pending[offset + 5] & 0xff) << 8);
            if (payloadLength > 65535) { offset += 2; continue; }
            int frameLength = payloadLength + 8; if (pending.length - offset < frameLength) break;
            if (checksum(pending, offset + 2, payloadLength + 4, pending[offset + 6 + payloadLength], pending[offset + 7 + payloadLength]))
                frames.add(new UbxFrame(pending[offset + 2] & 0xff, pending[offset + 3] & 0xff, Arrays.copyOfRange(pending, offset + 6, offset + 6 + payloadLength)));
            offset += frameLength;
        }
        pending = Arrays.copyOfRange(pending, offset, pending.length); return frames;
    }

    public static GrawCodec.Message toCanonical(UbxFrame frame) {
        if (frame.messageClass == 0x02 && frame.messageId == 0x15) return rawx(frame.payload);
        if (frame.messageClass == 0x02 && frame.messageId == 0x13) return sfrbx(frame.payload);
        return null;
    }

    private static GrawCodec.ObservationEpoch rawx(byte[] payload) {
        if (payload.length < 16) throw new IllegalArgumentException("Truncated UBX-RXM-RAWX");
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        double tow = in.getDouble(); int week = Short.toUnsignedInt(in.getShort()); int leap = in.get(); int count = Byte.toUnsignedInt(in.get());
        int receiverStatus = Byte.toUnsignedInt(in.get()); in.get(); in.getShort(); List<GrawCodec.Observation> observations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 32) throw new IllegalArgumentException("Truncated UBX-RXM-RAWX measurement");
            double pr = in.getDouble(), cp = in.getDouble(); float doppler = in.getFloat(); int gnss = Byte.toUnsignedInt(in.get());
            int sv = Byte.toUnsignedInt(in.get()), sig = Byte.toUnsignedInt(in.get()), freq = Byte.toUnsignedInt(in.get()); int lock = Short.toUnsignedInt(in.getShort());
            int cno = Byte.toUnsignedInt(in.get()), prStd = Byte.toUnsignedInt(in.get()), cpStd = Byte.toUnsignedInt(in.get()), doStd = Byte.toUnsignedInt(in.get());
            int tracking = Byte.toUnsignedInt(in.get()); in.get();
            observations.add(new GrawCodec.Observation(pr, cp, doppler, gnss, sv, sig, freq, lock, cno, prStd, cpStd, doStd, tracking));
        }
        return new GrawCodec.ObservationEpoch(tow, week, leap, receiverStatus, 1, observations);
    }

    private static GrawCodec.NavigationUpdate sfrbx(byte[] payload) {
        if (payload.length < 8) throw new IllegalArgumentException("Truncated UBX-RXM-SFRBX");
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int gnss = Byte.toUnsignedInt(in.get()), sv = Byte.toUnsignedInt(in.get()), sig = Byte.toUnsignedInt(in.get()), freq = Byte.toUnsignedInt(in.get());
        int words = Byte.toUnsignedInt(in.get()); in.get(); int version = Byte.toUnsignedInt(in.get()); in.get();
        if (in.remaining() < words * 4) throw new IllegalArgumentException("Truncated UBX-RXM-SFRBX words");
        List<Long> values = new ArrayList<>(words); for (int i = 0; i < words; i++) values.add(Integer.toUnsignedLong(in.getInt()));
        return new GrawCodec.NavigationUpdate(gnss, sv, sig, freq, version, values);
    }

    public static byte[] command(int messageClass, int messageId, byte[] payload) {
        byte[] out = new byte[payload.length + 8]; out[0] = (byte) 0xB5; out[1] = 0x62; out[2] = (byte) messageClass; out[3] = (byte) messageId;
        out[4] = (byte) payload.length; out[5] = (byte) (payload.length >>> 8); System.arraycopy(payload, 0, out, 6, payload.length);
        int a = 0, b = 0; for (int i = 2; i < out.length - 2; i++) { a = (a + (out[i] & 0xff)) & 0xff; b = (b + a) & 0xff; }
        out[out.length - 2] = (byte) a; out[out.length - 1] = (byte) b; return out;
    }

    private static boolean checksum(byte[] bytes, int offset, int length, byte expectedA, byte expectedB) {
        int a = 0, b = 0; for (int i = offset; i < offset + length; i++) { a = (a + (bytes[i] & 0xff)) & 0xff; b = (b + a) & 0xff; }
        return a == (expectedA & 0xff) && b == (expectedB & 0xff);
    }
}
