package server.agent.session.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** PocketSDR-AFS 방식의 AFS 동기 패턴 확정 규칙을 검증한다. */
class UdpSessionServiceTest {

  private static final byte[] SYNC = {
    (byte) 0xCC, 0x63, (byte) 0xF7, 0x45, 0x36, (byte) 0xF4, (byte) 0x9E, 0x04, (byte) 0xA0
  };

  @Test
  void damagedFrameIsRejectedAndConsecutiveNormalFramesAreConfirmed() {
    byte[] stream = new byte[750 * 4];
    putSync(stream, 0);
    putSync(stream, 6000);
    putSync(stream, 12000);
    putSync(stream, 18000);
    stream[0] ^= (byte) 0x80;

    assertEquals(
        List.of(6000L, 12000L, 18000L), UdpSessionService.findConfirmedSyncOffsets(stream));
  }

  @Test
  void isolatedPatternInsidePayloadIsNotAcceptedAsFrameSynchronization() {
    byte[] stream = new byte[750 * 3];
    putSync(stream, 0);
    putSync(stream, 6000);
    putSync(stream, 12000);
    putSync(stream, 1000);

    assertEquals(List.of(0L, 6000L, 12000L), UdpSessionService.findConfirmedSyncOffsets(stream));
  }

  private static void putSync(byte[] stream, int bitOffset) {
    for (int index = 0; index < 68; index++) {
      int value = (SYNC[index >>> 3] >>> (7 - (index & 7))) & 1;
      if (value == 1) {
        int target = bitOffset + index;
        stream[target >>> 3] |= (byte) (1 << (7 - (target & 7)));
      }
    }
  }
}
