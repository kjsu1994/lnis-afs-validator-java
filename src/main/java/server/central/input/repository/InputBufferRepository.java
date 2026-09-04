package server.central.input.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import server.central.input.entity.InputBufferEntity;
import server.central.input.entity.InputChunkEntity;
import server.central.input.service.GrawFileStorage;

@Repository
/** 입력 메타데이터와 청크 위치는 H2에, 실제 바이트는 파일에 저장한다. */
public class InputBufferRepository {
  private final InputMetadataJpaRepository metadata;
  private final InputChunkJpaRepository chunks;
  private final GrawFileStorage files;

  public InputBufferRepository(
      InputMetadataJpaRepository metadata, InputChunkJpaRepository chunks, GrawFileStorage files) {
    this.metadata = metadata;
    this.chunks = chunks;
    this.files = files;
  }

  public void save(InputBufferEntity value, Duration ttl) {
    metadata.save(value);
  }

  public Optional<InputBufferEntity> find(UUID id) {
    return metadata.findById(id);
  }

  public void putChunk(UUID id, long index, byte[] value, Duration ttl) {
    InputBufferEntity input =
        metadata
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Input not found: " + id));
    files.append(id, input.receivedSize(), value);
    chunks.save(new InputChunkEntity(id, index, input.receivedSize(), value.length));
  }

  public byte[] getChunk(UUID id, long index) {
    InputBufferEntity input = metadata.findById(id).orElse(null);
    InputChunkEntity chunk = chunks.findByInputIdAndChunkIndex(id, index).orElse(null);
    if (input == null || chunk == null) return null;
    return files.read(id, input.complete(), chunk.getFileOffset(), chunk.getByteLength());
  }

  public void touchChunks(UUID id, long count, Duration ttl) {}

  public void completeFile(UUID id) {
    files.complete(id);
  }

  public void delete(UUID id, long count) {
    chunks.deleteByInputId(id);
    metadata.deleteById(id);
    files.delete(id);
  }

  public List<InputBufferEntity> incompleteBefore(Instant cutoff) {
    return metadata.findByCompleteFalseAndCreatedAtBefore(cutoff);
  }

  public List<InputBufferEntity> completeBefore(Instant cutoff) {
    return metadata.findByCompleteTrueAndCompletedAtBefore(cutoff);
  }
}

interface InputMetadataJpaRepository extends JpaRepository<InputBufferEntity, UUID> {
  List<InputBufferEntity> findByCompleteFalseAndCreatedAtBefore(Instant cutoff);

  List<InputBufferEntity> findByCompleteTrueAndCompletedAtBefore(Instant cutoff);
}

interface InputChunkJpaRepository extends JpaRepository<InputChunkEntity, InputChunkEntity.Key> {
  Optional<InputChunkEntity> findByInputIdAndChunkIndex(UUID inputId, long chunkIndex);

  void deleteByInputId(UUID inputId);
}
