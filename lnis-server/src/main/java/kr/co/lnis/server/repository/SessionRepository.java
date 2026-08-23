package kr.co.lnis.server.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.LnisModels.AgentRole;
import kr.co.lnis.common.model.LnisModels.RoleResult;
import kr.co.lnis.server.entity.RedisEntities.TestSessionEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionRepository {
    public static final Duration TTL = Duration.ofHours(24); private final StringRedisTemplate redis; private final ObjectMapper json;
    public SessionRepository(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    private static String key(UUID id) { return "lnis:session:" + id; }
    public void save(TestSessionEntity value) { try { redis.opsForHash().put(key(value.sessionId()), "session", json.writeValueAsString(value)); redis.expire(key(value.sessionId()), TTL); } catch (Exception e) { throw new IllegalStateException(e); } }
    public Optional<TestSessionEntity> find(UUID id) { Object value = redis.opsForHash().get(key(id), "session"); if (value == null) return Optional.empty(); try { return Optional.of(json.readValue(value.toString(), TestSessionEntity.class)); } catch (Exception e) { throw new IllegalStateException(e); } }
    public void saveResult(RoleResult result) { try { redis.opsForHash().put(key(result.sessionId()), result.role() == AgentRole.SENDER ? "txResult" : "rxResult", json.writeValueAsString(result)); redis.expire(key(result.sessionId()), TTL); } catch (Exception e) { throw new IllegalStateException(e); } }
    public Optional<RoleResult> result(UUID id, AgentRole role) { Object value = redis.opsForHash().get(key(id), role == AgentRole.SENDER ? "txResult" : "rxResult"); if (value == null) return Optional.empty(); try { return Optional.of(json.readValue(value.toString(), RoleResult.class)); } catch (Exception e) { throw new IllegalStateException(e); } }
}

