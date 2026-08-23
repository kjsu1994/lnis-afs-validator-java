package kr.co.lnis.agent.afs;

import kr.co.lnis.common.codec.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class AfsRawFragmentCodec {
    public static final int BLOCK_BYTES = 105, HEADER_BYTES = 19, PAYLOAD_BYTES = 86;
    private AfsRawFragmentCodec() {}
    public record Fragment(long recordSequence, int fragmentIndex, int fragmentCount, long recordLength,
                           int payloadLength, long recordCrc32, byte[] payload) {}

    public static List<byte[]> fragment(long sequence, byte[] record) {
        if (record.length == 0) throw new IllegalArgumentException("Empty GRAW record");
        int count = (record.length + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES;
        if (count > 65535) throw new IllegalArgumentException("Too many fragments");
        long crc = Hashing.crc32(record); List<byte[]> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = i * PAYLOAD_BYTES, length = Math.min(PAYLOAD_BYTES, record.length - offset);
            ByteBuffer out = ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.BIG_ENDIAN);
            out.put((byte) 1).put((byte) ((i == 0 ? 1 : 0) | (i == count - 1 ? 2 : 0)))
                    .putInt((int) sequence).putShort((short) i).putShort((short) count).putInt(record.length)
                    .put((byte) length).putInt((int) crc).put(record, offset, length);
            result.add(out.array());
        }
        return result;
    }

    public static Fragment decode(byte[] block) {
        if (block.length != BLOCK_BYTES || block[0] != 1) throw new IllegalArgumentException("Invalid AFS custom block");
        ByteBuffer in = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN); in.position(2);
        long sequence = Integer.toUnsignedLong(in.getInt()); int index = Short.toUnsignedInt(in.getShort()); int count = Short.toUnsignedInt(in.getShort());
        long recordLength = Integer.toUnsignedLong(in.getInt()); int length = Byte.toUnsignedInt(in.get()); long crc = Integer.toUnsignedLong(in.getInt());
        if (count == 0 || index >= count || length > PAYLOAD_BYTES) throw new IllegalArgumentException("Invalid AFS fragment metadata");
        byte[] payload = new byte[length]; in.get(payload); return new Fragment(sequence, index, count, recordLength, length, crc, payload);
    }

    public static byte[] toSbBits(byte[] block) {
        if (block.length != BLOCK_BYTES) throw new IllegalArgumentException("AFS block must be 105 bytes");
        byte[] bits = new byte[846]; int messageType = 63;
        for (int i = 0; i < 6; i++) bits[i] = (byte) ((messageType >> (5 - i)) & 1);
        for (int i = 0; i < block.length * 8; i++) bits[6 + i] = (byte) ((block[i >>> 3] >> (7 - (i & 7))) & 1);
        return bits;
    }

    public static byte[] fromSbBits(byte[] bits) {
        if (bits.length != 846) throw new IllegalArgumentException("SB must have 846 bits");
        int type = 0; for (int i = 0; i < 6; i++) type = (type << 1) | bits[i];
        if (type != 63) throw new IllegalArgumentException("Unexpected custom message type");
        byte[] block = new byte[BLOCK_BYTES];
        for (int i = 0; i < block.length * 8; i++) block[i >>> 3] |= (byte) (bits[6 + i] << (7 - (i & 7)));
        return block;
    }
}

