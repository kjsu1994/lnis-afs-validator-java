package kr.co.lnis.server.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.server.entity.RedisEntities.InputBufferEntity;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InputBufferRepository {
    private final StringRedisTemplate text; private final RedisTemplate<String, byte[]> binary; private final ObjectMapper json;
    public InputBufferRepository(StringRedisTemplate text, RedisTemplate<String, byte[]> binary, ObjectMapper json) { this.text = text; this.binary = binary; this.json = json; }
    private static String meta(UUID id) { return "lnis:input:" + id + ":meta"; }
    private static String chunk(UUID id, long index) { return "lnis:input:" + id + ":chunk:" + index; }
    public void save(InputBufferEntity value, Duration ttl) { try { text.opsForValue().set(meta(value.inputId()), json.writeValueAsString(value), ttl); } catch (Exception e) { throw new IllegalStateException(e); } }
    public Optional<InputBufferEntity> find(UUID id) { String value = text.opsForValue().get(meta(id)); if (value == null) return Optional.empty(); try { return Optional.of(json.readValue(value, InputBufferEntity.class)); } catch (Exception e) { throw new IllegalStateException(e); } }
    public void putChunk(UUID id, long index, byte[] value, Duration ttl) { binary.opsForValue().set(chunk(id, index), value, ttl); }
    public byte[] getChunk(UUID id, long index) { return binary.opsForValue().get(chunk(id, index)); }
    public void touchChunks(UUID id, long count, Duration ttl) { for (long i = 0; i < count; i++) binary.expire(chunk(id, i), ttl); }
    public void delete(UUID id, long count) { text.delete(meta(id)); for (long i = 0; i < count; i++) binary.delete(chunk(id, i)); }
}

