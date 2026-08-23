package kr.co.lnis.protocol.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Sender와 Receiver 사이 UDP datagram의 header, payload 및 CRC를 처리한다.
 *
 * <p>모든 packet은 ASCII magic, protocol version, packet 종류, .NET 호환 UUID, sequence, GNSS 시간,
 * payload와 CRC32 순서로 구성된다. 숫자는 network byte order(big-endian)를 사용한다. payload 상한을
 * 1200 byte로 제한해 일반적인 MTU 환경에서 IP fragmentation 가능성을 낮춘다.
 */
public final class AfsPacketCodec {
    /** payload 시작 위치이자 고정 header byte 수다. */
    public static final int HEADER_LENGTH = 48;
    /** UDP packet 한 개에 넣을 수 있는 최대 application payload다. */
    public static final int MAXIMUM_PAYLOAD_LENGTH = 1200;
    private static final byte[] MAGIC = "LAFS".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;

    private AfsPacketCodec() {}

    /** 시험 handshake, frame, 종료 및 결과를 구분하는 wire packet 종류다. */
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

    /**
     * 인코딩 전 또는 디코딩 후의 UDP packet 값 객체다.
     *
     * <p>payload 배열은 생성 시 복사해 호출자가 나중에 원본 배열을 변경해도 packet 내용이 변하지 않는다.
     */
    public record Packet(
            /** SESSION_START, FRAME, RESULT 등 UDP 패킷의 용도다. */
            Kind kind,
            /** 패킷이 속한 시험 세션 UUID다. */
            UUID testId,
            /** 같은 종류 안에서 논리 패킷을 식별하는 unsigned 32-bit 순번이다. */
            long sequence,
            /** 반복 송신된 동일 논리 패킷 중 0부터 시작하는 복제본 번호다. */
            int copyIndex,
            /** AFS 신호 구성에 사용한 PRN 식별값이다. */
            int prn,
            /** 프레임의 GPS week 번호다. */
            int week,
            /** GPS week를 1,200초 단위로 나눈 구간 번호다. */
            int intervalOfWeek,
            /** 해당 구간 안 AFS TOI 값이며 범위는 0~99다. */
            int timeOfInterval,
            /** 패킷 송신 시각을 .NET UTC tick 단위로 기록한 값이다. */
            long sentUtcTicks,
            /** 패킷 종류별 application 데이터이며 최대 1,200 byte다. */
            byte[] payload) {
        public Packet { payload = payload == null ? new byte[0] : payload.clone(); }
    }

    /** Packet 필드를 검증하고 CRC32를 붙인 전송용 byte 배열로 직렬화한다. */
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

    /** magic, version, 길이, reserved byte와 CRC32를 모두 확인한 뒤 Packet으로 복원한다. */
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
