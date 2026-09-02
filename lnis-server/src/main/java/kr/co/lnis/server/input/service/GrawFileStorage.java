package kr.co.lnis.server.input.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import kr.co.lnis.server.config.StorageProperties;
import org.springframework.stereotype.Component;

/** 대용량 GRAW를 H2 밖의 단일 파일로 안전하게 순차 저장한다. */
@Component
public class GrawFileStorage {
  private final Path directory;

  public GrawFileStorage(StorageProperties properties) {
    directory = properties.getDataDirectory().resolve("files/inputs").toAbsolutePath().normalize();
  }

  @PostConstruct
  void initialize() {
    try {
      Files.createDirectories(directory);
      Path probe = Files.createTempFile(directory, ".write-test-", ".tmp");
      Files.delete(probe);
    } catch (IOException error) {
      throw new IllegalStateException("GRAW 저장 경로에 쓸 수 없습니다: " + directory, error);
    }
  }

  public synchronized void append(UUID id, long expectedOffset, byte[] bytes) {
    try (FileChannel channel =
        FileChannel.open(
            partial(id),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      if (channel.size() > expectedOffset) channel.truncate(expectedOffset);
      if (channel.size() != expectedOffset)
        throw new IllegalStateException("GRAW 파일 길이와 DB가 일치하지 않습니다.");
      channel.position(expectedOffset);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(false);
    } catch (IOException error) {
      throw new IllegalStateException("GRAW 청크 파일 저장에 실패했습니다.", error);
    }
  }

  public byte[] read(UUID id, boolean complete, long offset, int length) {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    try (FileChannel channel =
        FileChannel.open(complete ? completed(id) : partial(id), StandardOpenOption.READ)) {
      channel.position(offset);
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
    } catch (IOException error) {
      throw new IllegalStateException("GRAW 청크 파일 조회에 실패했습니다.", error);
    }
    if (buffer.hasRemaining()) throw new IllegalStateException("GRAW 파일이 예상보다 짧습니다.");
    return buffer.array();
  }

  public synchronized void complete(UUID id) {
    try {
      Files.move(
          partial(id),
          completed(id),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      try {
        Files.move(partial(id), completed(id), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException error) {
        throw new IllegalStateException("GRAW 파일 확정에 실패했습니다.", error);
      }
    } catch (IOException error) {
      throw new IllegalStateException("GRAW 파일 확정에 실패했습니다.", error);
    }
  }

  public synchronized void delete(UUID id) {
    try {
      Files.deleteIfExists(partial(id));
      Files.deleteIfExists(completed(id));
    } catch (IOException error) {
      throw new IllegalStateException("GRAW 파일 삭제에 실패했습니다.", error);
    }
  }

  private Path partial(UUID id) {
    return directory.resolve(id + ".part");
  }

  private Path completed(UUID id) {
    return directory.resolve(id + ".graw");
  }
}
