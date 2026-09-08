package server.shared.codec;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 조각 누락·중복으로 서로 다른 입력을 계산기에 넘기지 않도록 검사한다. */
class DtnChunksTest {
  @Test void reassemblesAndRejectsDuplicateAndOversizedChunks() {
    DtnChunks chunks = new DtnChunks();
    assertNull(chunks.append(0, false, new byte[]{1}));
    assertThrows(IllegalArgumentException.class, () -> chunks.append(0, false, new byte[]{1}));
    assertArrayEquals(new byte[]{1,2}, chunks.append(1, true, new byte[]{2}));
    assertThrows(IllegalArgumentException.class, () -> chunks.append(2, true, new byte[]{3}));
    assertThrows(IllegalArgumentException.class, () -> new DtnChunks().append(0, true, new byte[DtnChunks.CHUNK_BYTES+1]));
  }
}
