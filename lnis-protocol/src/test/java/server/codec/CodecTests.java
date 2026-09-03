package server.codec;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 공통 GRAW 및 UDP wire codec의 인코딩·디코딩 호환성을 검증한다. */
class CodecTests {
    @Test void afsPacketRoundTrips() {
        var packet = new AfsPacketCodec.Packet(AfsPacketCodec.Kind.FRAME, UUID.randomUUID(), 42, 2, 8, 2300, 10, 7, 638000000000000000L, new byte[]{1,2,3});
        var decoded = AfsPacketCodec.decode(AfsPacketCodec.encode(packet));
        assertEquals(packet.kind(), decoded.kind());
        assertEquals(packet.testId(), decoded.testId());
        assertEquals(packet.sequence(), decoded.sequence());
        assertArrayEquals(packet.payload(), decoded.payload());
    }
    @Test void uuidUsesCSharpBigEndianGuidLayout() {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        var packet = new AfsPacketCodec.Packet(AfsPacketCodec.Kind.PROBE, id, 0, 0, 8, 0, 0, 0, 0, new byte[0]);
        byte[] encoded = AfsPacketCodec.encode(packet);
        assertArrayEquals(new byte[]{
                        0x00, 0x11, 0x22, 0x33,
                        0x44, 0x55, 0x66, 0x77,
                        (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb,
                        (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff},
                java.util.Arrays.copyOfRange(encoded, 8, 24));
    }
    @Test void dropSimulationIsDeterministic() {
        assertEquals(AfsDropSimulator.shouldDrop(100, 2, 25, 7), AfsDropSimulator.shouldDrop(100, 2, 25, 7));
        assertFalse(AfsDropSimulator.shouldDrop(1, 0, 0, 1));
        assertTrue(AfsDropSimulator.shouldDrop(1, 0, 100, 1));
    }
    @Test void crcMatchesKnownVector() { assertEquals(0xcbf43926L, Hashing.crc32("123456789".getBytes())); }
    @Test void observationGrawRoundTrips() {
        var message=new GrawCodec.ObservationEpoch(123.5,2300,18,1,1,List.of(new GrawCodec.Observation(10.25,20.5,-3.5f,0,8,1,0,55,45,1,2,3,7)));
        var envelope=new GrawCodec.Envelope(UUID.randomUUID(),UUID.randomUUID(),9,Instant.parse("2026-01-01T00:00:00.123456Z"),message);
        var decoded = GrawCodec.decode(GrawCodec.encode(envelope));
        assertEquals(envelope.testId(), decoded.testId());
        assertEquals(message, decoded.message());
        assertEquals(envelope.capturedAt(), decoded.capturedAt());
    }
}
