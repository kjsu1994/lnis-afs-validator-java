package kr.co.lnis.server.session.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.protocol.model.LnisModels.RoleResult;
import kr.co.lnis.server.session.entity.TestSessionEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
/** 시험 세션과 TX/RX 결과를 하나의 Redis Hash로 묶어 보관한다. */
public class SessionRepository {
    public static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public SessionRepository(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }
    private static String key(UUID id) { return "lnis:session:" + id; }
    public void save(TestSessionEntity value) {
        try {
            redis.opsForHash().put(
                    key(value.sessionId()), "session", json.writeValueAsString(value));
            redis.expire(key(value.sessionId()), TTL);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<TestSessionEntity> find(UUID id) {
        Object value = redis.opsForHash().get(key(id), "session");
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value.toString(), TestSessionEntity.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void saveResult(RoleResult result) {
        try {
            String field = result.role() == AgentRole.SENDER ? "txResult" : "rxResult";
            redis.opsForHash().put(
                    key(result.sessionId()), field, json.writeValueAsString(result));
            redis.expire(key(result.sessionId()), TTL);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<RoleResult> result(UUID id, AgentRole role) {
        String field = role == AgentRole.SENDER ? "txResult" : "rxResult";
        Object value = redis.opsForHash().get(key(id), field);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value.toString(), RoleResult.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
