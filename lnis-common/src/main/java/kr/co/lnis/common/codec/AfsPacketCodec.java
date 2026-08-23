package kr.co.lnis.common.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Sender와 Receiver 사이 UDP datagram의 header, payload 및 CRC를 처리한다. */
public final class AfsPacketCodec {
    public static final int HEADER_LENGTH = 48;
    public static final int MAXIMUM_PAYLOAD_LENGTH = 1200;
    private static final byte[] MAGIC = "LAFS".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;

    private AfsPacketCodec() {}

    public enum Kind {
        TIME_SYNC_REQUEST(1), TIME_SYNC_RESPONSE(2), SESSION_START(3), FRAME(4),
        PROBE(5), PROBE_RESPONSE(6), SESSION_END(7), RESULT(8);
        private final int wire;
        Kind(int wire) { this.wire = wire; }
        public int wire() { return wire; }
        static Kind fromWire(int value) {
            for (Kind kind : values()) if (kind.wire == value) return kind;
            throw new IllegalArgumentException("Unknown AFS packet kind " + value);
        }
    }

    public record Packet(Kind kind, UUID testId, long sequence, int copyIndex, int prn,
                         int week, int intervalOfWeek, int timeOfInterval, long sentUtcTicks, byte[] payload) {
        public Packet { payload = payload == null ? new byte[0] : payload.clone(); }
    }

    public static byte[] encode(Packet packet) {
        if (packet.payload.length > MAXIMUM_PAYLOAD_LENGTH) throw new IllegalArgumentException("AFS UDP payload exceeds 1200 bytes");
        ByteBuffer out = ByteBuffer.allocate(HEADER_LENGTH + packet.payload.length + 4).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).put(VERSION).put((byte) packet.kind.wire()).putShort((short) HEADER_LENGTH);
        putDotNetGuid(out, packet.testId);
        out.putInt(Math.toIntExact(packet.sequence & 0xffff_ffffL));
        out.put((byte) packet.copyIndex).put((byte) packet.prn).putShort((short) packet.week)
                .putShort((short) packet.intervalOfWeek).put((byte) packet.timeOfInterval).put((byte) 0)
                .putLong(packet.sentUtcTicks).putShort((short) packet.payload.length).putShort((short) 0).put(packet.payload);
        long crc = Hashing.crc32(out.array(), 0, out.position());
        out.putInt((int) crc);
        return out.array();
    }

    public static Packet decode(byte[] bytes) {
        if (bytes.length < HEADER_LENGTH + 4 || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, 4)))
            throw new IllegalArgumentException("Invalid AFS UDP magic");
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        in.position(4);
        int version = Byte.toUnsignedInt(in.get());
        Kind kind = Kind.fromWire(Byte.toUnsignedInt(in.get()));
        int headerLength = Short.toUnsignedInt(in.getShort());
        if (version != VERSION || headerLength != HEADER_LENGTH) throw new IllegalArgumentException("Unsupported AFS UDP header");
        UUID testId = getDotNetGuid(in);
        long sequence = Integer.toUnsignedLong(in.getInt());
        int copyIndex = Byte.toUnsignedInt(in.get());
        int prn = Byte.toUnsignedInt(in.get());
        int week = Short.toUnsignedInt(in.getShort());
        int interval = Short.toUnsignedInt(in.getShort());
        int toi = Byte.toUnsignedInt(in.get());
        if (in.get() != 0) throw new IllegalArgumentException("Reserved header byte is non-zero");
        long ticks = in.getLong();
        int payloadLength = Short.toUnsignedInt(in.getShort());
        if (in.getShort() != 0 || payloadLength > MAXIMUM_PAYLOAD_LENGTH || bytes.length != HEADER_LENGTH + payloadLength + 4)
            throw new IllegalArgumentException("Invalid AFS UDP payload length");
        long expected = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, bytes.length - 4, 4).order(ByteOrder.BIG_ENDIAN).getInt());
        if (Hashing.crc32(bytes, 0, bytes.length - 4) != expected) throw new IllegalArgumentException("AFS UDP CRC32 mismatch");
        byte[] payload = new byte[payloadLength];
        in.get(payload);
        return new Packet(kind, testId, sequence, copyIndex, prn, week, interval, toi, ticks, payload);
    }

    static void putDotNetGuid(ByteBuffer out, UUID uuid) {
        // C# uses Guid.TryWriteBytes(..., bigEndian: true), which is RFC-4122 byte order.
        out.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
    }

    static UUID getDotNetGuid(ByteBuffer in) {
        return new UUID(in.getLong(), in.getLong());
    }
}
