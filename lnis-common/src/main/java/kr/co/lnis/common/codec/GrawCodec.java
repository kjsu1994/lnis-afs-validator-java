package kr.co.lnis.common.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class GrawCodec {
    private static final byte[] MAGIC = "LGRW".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_LENGTH = 62;
    public static final int MAXIMUM_RECORD_LENGTH = 1_048_642;
    private GrawCodec() {}

    public enum MessageType { OBSERVATION_EPOCH(1), NAVIGATION_UPDATE(2), RECEIVER_METADATA(3);
        final int wire; MessageType(int wire) { this.wire = wire; }
        static MessageType fromWire(int value) { for (var x : values()) if (x.wire == value) return x; throw new IllegalArgumentException("Unknown GRAW message type"); }
    }
    public enum Constellation { GPS(0), SBAS(1), GALILEO(2), BEIDOU(3), IMES(4), QZSS(5), GLONASS(6), NAVIC(7), UNKNOWN(255);
        final int wire; Constellation(int wire) { this.wire = wire; }
        public static Constellation fromUblox(int value) { for (var x : values()) if (x.wire == value) return x; return UNKNOWN; }
    }
    public sealed interface Message permits ObservationEpoch, NavigationUpdate, ReceiverMetadata { MessageType type(); }
    public record Observation(double pseudorangeMeters, double carrierPhaseCycles, float dopplerHz, int constellationId,
                              int satelliteId, int signalId, int frequencyId, int lockTimeMilliseconds,
                              int carrierToNoiseDbHz, int pseudorangeStdDev, int carrierPhaseStdDev,
                              int dopplerStdDev, int trackingStatus) {}
    public record ObservationEpoch(double receiverTowSeconds, int week, int leapSeconds, int receiverStatus,
                                   int rawxVersion, List<Observation> observations) implements Message {
        @Override public MessageType type() { return MessageType.OBSERVATION_EPOCH; }
    }
    public record NavigationUpdate(int constellationId, int satelliteId, int signalId, int frequencyId,
                                   int sfrbxVersion, List<Long> words) implements Message {
        @Override public MessageType type() { return MessageType.NAVIGATION_UPDATE; }
    }
    public record ReceiverMetadata(String receiverModel, String firmwareVersion, String portName,
                                   int baudRate, String sessionName) implements Message {
        @Override public MessageType type() { return MessageType.RECEIVER_METADATA; }
    }
    public record Envelope(UUID testId, UUID messageId, long sequence, Instant capturedAt, Message message) {}

    public static byte[] encode(Envelope envelope) {
        byte[] payload = encodePayload(envelope.message);
        if (payload.length > 1024 * 1024) throw new IllegalArgumentException("GRAW payload too large");
        ByteBuffer out = ByteBuffer.allocate(HEADER_LENGTH + payload.length + 4).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).putShort((short) 1).put((byte) envelope.message.type().wire).put((byte) 0)
                .putShort((short) HEADER_LENGTH).putInt(payload.length);
        AfsPacketCodec.putDotNetGuid(out, envelope.testId);
        AfsPacketCodec.putDotNetGuid(out, envelope.messageId);
        long micros = Math.addExact(Math.multiplyExact(envelope.capturedAt.getEpochSecond(), 1_000_000), envelope.capturedAt.getNano() / 1_000);
        out.putLong(envelope.sequence).putLong(micros).put(payload);
        out.putInt((int) Hashing.crc32(out.array(), 0, out.position()));
        return out.array();
    }

    public static Envelope decode(byte[] record) {
        if (record.length < HEADER_LENGTH + 4 || !Arrays.equals(MAGIC, Arrays.copyOf(record, 4))) throw new IllegalArgumentException("Invalid GRAW magic");
        ByteBuffer in = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN); in.position(4);
        int version = Short.toUnsignedInt(in.getShort()); MessageType type = MessageType.fromWire(Byte.toUnsignedInt(in.get()));
        int reserved = Byte.toUnsignedInt(in.get()); int header = Short.toUnsignedInt(in.getShort()); int length = in.getInt();
        if (version != 1 || reserved != 0 || header != HEADER_LENGTH || length < 0 || length > 1024 * 1024 || record.length != HEADER_LENGTH + length + 4)
            throw new IllegalArgumentException("Unsupported GRAW header");
        UUID testId = AfsPacketCodec.getDotNetGuid(in); UUID messageId = AfsPacketCodec.getDotNetGuid(in);
        long sequence = in.getLong(); long micros = in.getLong();
        long expected = Integer.toUnsignedLong(ByteBuffer.wrap(record, record.length - 4, 4).order(ByteOrder.BIG_ENDIAN).getInt());
        if (Hashing.crc32(record, 0, record.length - 4) != expected) throw new IllegalArgumentException("GRAW CRC32 mismatch");
        byte[] payload = new byte[length]; in.get(payload);
        return new Envelope(testId, messageId, sequence, Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000), decodePayload(type, payload));
    }

    public static List<byte[]> splitLengthPrefixed(byte[] data) {
        List<byte[]> records = new ArrayList<>(); ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (in.hasRemaining()) {
            if (in.remaining() < 4) throw new IllegalArgumentException("Truncated GRAW length");
            int length = in.getInt();
            if (length <= 0 || length > MAXIMUM_RECORD_LENGTH || in.remaining() < length) throw new IllegalArgumentException("Invalid GRAW record length");
            byte[] record = new byte[length]; in.get(record); decode(record); records.add(record);
        }
        return records;
    }

    private static byte[] encodePayload(Message message) {
        ByteBuffer out = ByteBuffer.allocate(estimate(message)).order(ByteOrder.BIG_ENDIAN);
        switch (message) {
            case ObservationEpoch x -> {
                out.putDouble(x.receiverTowSeconds).putShort((short) x.week).put((byte) x.leapSeconds).put((byte) x.receiverStatus)
                        .put((byte) x.rawxVersion).putShort((short) x.observations.size());
                for (Observation o : x.observations) out.put((byte) o.constellationId).put((byte) o.satelliteId).put((byte) o.signalId)
                        .put((byte) o.frequencyId).putDouble(o.pseudorangeMeters).putDouble(o.carrierPhaseCycles).putFloat(o.dopplerHz)
                        .putShort((short) o.lockTimeMilliseconds).put((byte) o.carrierToNoiseDbHz).put((byte) o.pseudorangeStdDev)
                        .put((byte) o.carrierPhaseStdDev).put((byte) o.dopplerStdDev).put((byte) o.trackingStatus);
            }
            case NavigationUpdate x -> {
                out.put((byte) x.constellationId).put((byte) x.satelliteId).put((byte) x.signalId).put((byte) x.frequencyId)
                        .put((byte) x.sfrbxVersion).putShort((short) x.words.size());
                x.words.forEach(word -> out.putInt((int) (word & 0xffff_ffffL)));
            }
            case ReceiverMetadata x -> { putText(out, x.receiverModel); putText(out, x.firmwareVersion); putText(out, x.portName); out.putInt(x.baudRate); putText(out, x.sessionName); }
        }
        return Arrays.copyOf(out.array(), out.position());
    }

    private static Message decodePayload(MessageType type, byte[] payload) {
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        Message result;
        if (type == MessageType.OBSERVATION_EPOCH) {
            double tow = in.getDouble(); int week = Short.toUnsignedInt(in.getShort()); int leap = in.get(); int status = Byte.toUnsignedInt(in.get());
            int version = Byte.toUnsignedInt(in.get()); int count = Short.toUnsignedInt(in.getShort()); List<Observation> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int gnss = Byte.toUnsignedInt(in.get()), sv = Byte.toUnsignedInt(in.get()), sig = Byte.toUnsignedInt(in.get()), freq = Byte.toUnsignedInt(in.get());
                list.add(new Observation(in.getDouble(), in.getDouble(), in.getFloat(), gnss, sv, sig, freq,
                        Short.toUnsignedInt(in.getShort()), Byte.toUnsignedInt(in.get()), Byte.toUnsignedInt(in.get()),
                        Byte.toUnsignedInt(in.get()), Byte.toUnsignedInt(in.get()), Byte.toUnsignedInt(in.get())));
            }
            result = new ObservationEpoch(tow, week, leap, status, version, list);
        } else if (type == MessageType.NAVIGATION_UPDATE) {
            int gnss = Byte.toUnsignedInt(in.get()), sv = Byte.toUnsignedInt(in.get()), sig = Byte.toUnsignedInt(in.get()), freq = Byte.toUnsignedInt(in.get()), version = Byte.toUnsignedInt(in.get());
            int count = Short.toUnsignedInt(in.getShort()); List<Long> words = new ArrayList<>(count); for (int i = 0; i < count; i++) words.add(Integer.toUnsignedLong(in.getInt()));
            result = new NavigationUpdate(gnss, sv, sig, freq, version, words);
        } else result = new ReceiverMetadata(getText(in), getText(in), getText(in), in.getInt(), getText(in));
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing GRAW payload bytes");
        return result;
    }

    private static int estimate(Message message) {
        if (message instanceof ObservationEpoch x) return 15 + x.observations.size() * 31;
        if (message instanceof NavigationUpdate x) return 7 + x.words.size() * 4;
        ReceiverMetadata x = (ReceiverMetadata) message;
        return 4 + 2 * 4 + utf8(x.receiverModel).length + utf8(x.firmwareVersion).length + utf8(x.portName).length + utf8(x.sessionName).length;
    }
    private static byte[] utf8(String value) { return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8); }
    private static void putText(ByteBuffer out, String value) { byte[] bytes = utf8(value); out.putShort((short) bytes.length).put(bytes); }
    private static String getText(ByteBuffer in) { int length = Short.toUnsignedInt(in.getShort()); byte[] bytes = new byte[length]; in.get(bytes); return new String(bytes, StandardCharsets.UTF_8); }
}
