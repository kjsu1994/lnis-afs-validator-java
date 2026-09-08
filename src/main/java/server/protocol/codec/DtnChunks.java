package server.protocol.codec;

import java.io.ByteArrayOutputStream;
import server.protocol.model.DtnModels;

/** DTN JSON은 WebSocket 최대 메시지 크기보다 작게 나눠 순서와 총 크기를 검증한다. */
public final class DtnChunks {
  public static final int CHUNK_BYTES = 196608;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private int next;
  private boolean completed;
  public synchronized byte[] append(int index, boolean last, byte[] chunk) {
    if (completed || index != next || chunk.length == 0 || chunk.length > CHUNK_BYTES
        || (long)bytes.size()+chunk.length > DtnModels.MAX_JSON_BYTES)
      throw new IllegalArgumentException("DTN chunk 순서 또는 크기 오류");
    bytes.writeBytes(chunk);
    next++;
    if (!last) return null;
    completed = true;
    return bytes.toByteArray();
  }
}
