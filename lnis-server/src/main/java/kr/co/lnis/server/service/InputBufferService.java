package kr.co.lnis.server.service;

import kr.co.lnis.common.model.LnisModels.InputKind;
import kr.co.lnis.server.entity.RedisEntities.InputBufferEntity;
import kr.co.lnis.server.repository.InputBufferRepository;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class InputBufferService {
    public static final int CHUNK_SIZE = 1024 * 1024; private static final Duration INCOMPLETE_TTL = Duration.ofHours(1), COMPLETE_TTL = Duration.ofHours(24);
    private final InputBufferRepository repository;
    public InputBufferService(InputBufferRepository repository) { this.repository = repository; }
    public InputBufferEntity create(String fileName, long declaredSize, InputKind kind) {
        UUID id = UUID.randomUUID(); var value = new InputBufferEntity(id, kind == null ? InputKind.GRAW_UPLOAD : kind, fileName, declaredSize, 0, 0, 0, null, false, Instant.now(), null);
        repository.save(value, INCOMPLETE_TTL); return value;
    }
    public synchronized InputBufferEntity append(UUID id, long index, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > CHUNK_SIZE) throw new IllegalArgumentException("Chunk must contain 1 to 1048576 bytes");
        var current = get(id); if (current.complete()) throw new IllegalStateException("Input is already complete");
        if (index != current.chunkCount()) throw new IllegalArgumentException("Expected chunk " + current.chunkCount());
        repository.putChunk(id, index, bytes, INCOMPLETE_TTL);
        var updated = new InputBufferEntity(id, current.kind(), current.fileName(), current.declaredSize(), current.receivedSize() + bytes.length,
                current.chunkCount() + 1, 0, null, false, current.createdAt(), null); repository.save(updated, INCOMPLETE_TTL); return updated;
    }
    public synchronized InputBufferEntity complete(UUID id) {
        var current = get(id); GrawStreamingValidator validator = new GrawStreamingValidator();
        for (long i = 0; i < current.chunkCount(); i++) { byte[] chunk = repository.getChunk(id, i); if (chunk == null) throw new IllegalStateException("Missing input chunk " + i); validator.push(chunk); }
        var result = validator.finish(); if (current.declaredSize() > 0 && current.declaredSize() != result.size()) throw new IllegalArgumentException("Uploaded size does not match declaration");
        var complete = new InputBufferEntity(id, current.kind(), current.fileName(), current.declaredSize(), result.size(), current.chunkCount(), result.records(), result.sha256(), true, current.createdAt(), Instant.now());
        repository.save(complete, COMPLETE_TTL); repository.touchChunks(id, current.chunkCount(), COMPLETE_TTL); return complete;
    }
    public InputBufferEntity get(UUID id) { return repository.find(id).orElseThrow(() -> new IllegalArgumentException("Input not found: " + id)); }
    public byte[] chunk(UUID id, long index) { byte[] value = repository.getChunk(id, index); if (value == null) throw new IllegalArgumentException("Input chunk not found"); return value; }
    public void remove(UUID id) { var input = get(id); repository.delete(id, input.chunkCount()); }
}

