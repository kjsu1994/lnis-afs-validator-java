package kr.co.lnis.server.service;

import kr.co.lnis.common.codec.GrawCodec;
import kr.co.lnis.common.codec.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

final class GrawStreamingValidator {
    private final MessageDigest sha = Hashing.sha256Digest(); private byte[] pending = new byte[0]; private long size, records;
    void push(byte[] chunk) {
        sha.update(chunk); size += chunk.length; byte[] joined = Arrays.copyOf(pending, pending.length + chunk.length); System.arraycopy(chunk, 0, joined, pending.length, chunk.length); pending = joined;
        int offset = 0;
        while (pending.length - offset >= 4) {
            int length = ByteBuffer.wrap(pending, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (length <= 0 || length > GrawCodec.MAXIMUM_RECORD_LENGTH) throw new IllegalArgumentException("Invalid capture.graw record length " + length);
            if (pending.length - offset - 4 < length) break;
            GrawCodec.decode(Arrays.copyOfRange(pending, offset + 4, offset + 4 + length)); records++; offset += 4 + length;
        }
        if (offset > 0) pending = Arrays.copyOfRange(pending, offset, pending.length);
    }
    Result finish() { if (size == 0 || records == 0 || pending.length != 0) throw new IllegalArgumentException("capture.graw is empty or truncated"); return new Result(size, records, Hashing.hex(sha.digest())); }
    record Result(long size, long records, String sha256) {}
}

