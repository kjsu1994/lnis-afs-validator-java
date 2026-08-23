package kr.co.lnis.server.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.server.agent.entity.AgentEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.*;

@Repository
/** Agent 접속 상태를 24시간 TTL의 Redis Hash로 관리한다. */
public class AgentRepository {
    private static final String INDEX = "lnis:agents";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public AgentRepository(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void save(AgentEntity agent) {
        try {
            redis.opsForHash().put(INDEX, agent.agentId(), json.writeValueAsString(agent));
            redis.expire(INDEX, Duration.ofDays(1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<AgentEntity> find(String id) {
        return parse(redis.opsForHash().get(INDEX, id));
    }

    public List<AgentEntity> findAll() {
        return redis.opsForHash().values(INDEX).stream()
                .map(this::parse)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(AgentEntity::agentId))
                .toList();
    }

    public void remove(String id) {
        redis.opsForHash().delete(INDEX, id);
    }

    private Optional<AgentEntity> parse(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value.toString(), AgentEntity.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
