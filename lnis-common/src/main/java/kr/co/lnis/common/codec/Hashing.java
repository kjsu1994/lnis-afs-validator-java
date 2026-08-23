package kr.co.lnis.common.codec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}

    public static long crc32(byte[] data) { return crc32(data, 0, data.length); }

    public static long crc32(byte[] data, int offset, int length) {
        long crc = 0xffff_ffffL;
        for (int p = offset; p < offset + length; p++) {
            crc ^= data[p] & 0xffL;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb8_8320L & -(crc & 1));
        }
        return (~crc) & 0xffff_ffffL;
    }

    public static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String hex(byte[] value) { return HexFormat.of().withUpperCase().formatHex(value); }
}

