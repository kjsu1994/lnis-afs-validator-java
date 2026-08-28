package kr.co.lnis.server.input.service;

import kr.co.lnis.protocol.model.LnisModels.InputKind;
import kr.co.lnis.server.input.entity.InputBufferEntity;
import kr.co.lnis.server.input.repository.InputBufferRepository;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
/**
 * GRAW 입력의 생성, 순차 청크 수신, 무결성 검증과 TTL 전환을 담당한다.
 *
 * <p>Repository의 Redis 접근을 감싸는 업무 계층으로, 청크 크기와 순서를 강제하고 완료 전후 TTL을
 * 다르게 적용한다. complete가 성공하기 전까지 해당 입력은 시험 세션에서 사용할 수 없다.
 */
public class InputBufferService {
    public static final int CHUNK_SIZE = 1024 * 1024;
    private static final Duration INCOMPLETE_TTL = Duration.ofHours(1);
    private static final Duration COMPLETE_TTL = Duration.ofHours(24);
    private final InputBufferRepository repository;
    public InputBufferService(InputBufferRepository repository) { this.repository = repository; }
    /** 메타데이터만 먼저 생성하고 미완성 입력 TTL 1시간을 적용한다. */
    public InputBufferEntity create(String fileName, long declaredSize, InputKind kind) {
        UUID id = UUID.randomUUID();
        var value = new InputBufferEntity(
                id,
                kind == null ? InputKind.GRAW_UPLOAD : kind,
                fileName,
                declaredSize,
                0,
                0,
                0,
                null,
                false,
                Instant.now(),
                null);
        repository.save(value, INCOMPLETE_TTL);
        return value;
    }
    /**
     * 다음 순번의 바이너리 청크를 저장한다.
     *
     * <p>동일 입력에 대한 동시 append로 chunkCount가 꼬이지 않도록 method를 동기화한다.
     */
    public synchronized InputBufferEntity append(UUID id, long index, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > CHUNK_SIZE) {
            throw new IllegalArgumentException("Chunk must contain 1 to 1048576 bytes");
        }
        var current = get(id);
        if (current.complete()) {
            throw new IllegalStateException("Input is already complete");
        }
        if (index != current.chunkCount()) {
            throw new IllegalArgumentException("Expected chunk " + current.chunkCount());
        }
        // 바이너리를 먼저 저장한 뒤 메타데이터의 chunkCount를 올려 조회자가 없는 청크를 참조하지 않게 한다.
        repository.putChunk(id, index, bytes, INCOMPLETE_TTL);
        var updated = new InputBufferEntity(
                id,
                current.kind(),
                current.fileName(),
                current.declaredSize(),
                current.receivedSize() + bytes.length,
                current.chunkCount() + 1,
                0,
                null,
                false,
                current.createdAt(),
                null);
        repository.save(updated, INCOMPLETE_TTL);
        return updated;
    }
    /** 전체 청크를 순서대로 검증하고 완료 메타데이터와 24시간 TTL을 확정한다. */
    public synchronized InputBufferEntity complete(UUID id) {
        var current = get(id);
        // 전체 파일을 한 배열로 합치지 않고 청크를 순차 공급해 큰 GRAW에서도 검증 메모리를 제한한다.
        GrawStreamingValidator validator = new GrawStreamingValidator();
        for (long i = 0; i < current.chunkCount(); i++) {
            byte[] chunk = repository.getChunk(id, i);
            if (chunk == null) {
                throw new IllegalStateException("Missing input chunk " + i);
            }
            validator.push(chunk);
        }
        var result = validator.finish();
        // API에 선언된 파일 크기와 실제 wire record 검증 결과를 모두 통과해야 complete=true가 된다.
        if (current.declaredSize() > 0 && current.declaredSize() != result.size()) {
            throw new IllegalArgumentException("Uploaded size does not match declaration");
        }
        var complete = new InputBufferEntity(
                id,
                current.kind(),
                current.fileName(),
                current.declaredSize(),
                result.size(),
                current.chunkCount(),
                result.records(),
                result.sha256(),
                true,
                current.createdAt(),
                Instant.now());
        repository.save(complete, COMPLETE_TTL);
        // 메타데이터만 먼저 만료돼 고아 청크가 남지 않도록 모든 청크 TTL도 같은 시점으로 연장한다.
        repository.touchChunks(id, current.chunkCount(), COMPLETE_TTL);
        return complete;
    }
    public InputBufferEntity get(UUID id) {
        return repository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Input not found: " + id));
    }

    public byte[] chunk(UUID id, long index) {
        byte[] value = repository.getChunk(id, index);
        if (value == null) {
            throw new IllegalArgumentException("Input chunk not found");
        }
        return value;
    }

    public void remove(UUID id) {
        var input = get(id);
        repository.delete(id, input.chunkCount());
    }
}
