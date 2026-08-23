package kr.co.lnis.server.frameevidence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.frameevidence.entity.FrameEvidenceEntity;
import kr.co.lnis.server.session.repository.SessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Sender/Receiver 증거를 서로 다른 Redis Hash field에 저장해 동시 수신 충돌을 막는다. */
@Repository
public class FrameEvidenceRepository {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public FrameEvidenceRepository(
            StringRedisTemplate redis,
            ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void save(
            UUID sessionId,
            AgentRole role,
            FrameEvidenceMessage evidence) {
        try {
            FrameEvidenceEntity entity = new FrameEvidenceEntity(
                    sessionId,
                    role,
                    evidence.frameIndex(),
                    evidence,
                    Instant.now());
            redis.opsForHash().put(
                    key(sessionId),
                    field(role, evidence.frameIndex()),
                    json.writeValueAsString(entity));
            redis.expire(key(sessionId), SessionRepository.TTL);
        } catch (Exception error) {
            throw new IllegalStateException("AFS 프레임 증거 저장에 실패했습니다.", error);
        }
    }

    public Optional<FrameEvidenceEntity> find(
            UUID sessionId,
            AgentRole role,
            int frameIndex) {
        Object value = redis.opsForHash().get(
                key(sessionId),
                field(role, frameIndex));
        return decode(value);
    }

    public List<FrameEvidenceEntity> findAll(UUID sessionId) {
        List<FrameEvidenceEntity> values = new ArrayList<>();
        for (Object value : redis.opsForHash().values(key(sessionId))) {
            decode(value).ifPresent(values::add);
        }
        return values;
    }

    private Optional<FrameEvidenceEntity> decode(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(
                    value.toString(),
                    FrameEvidenceEntity.class));
        } catch (Exception error) {
            throw new IllegalStateException("AFS 프레임 증거 조회에 실패했습니다.", error);
        }
    }

    private static String key(UUID sessionId) {
        return "lnis:frame-evidence:" + sessionId;
    }

    private static String field(AgentRole role, int frameIndex) {
        return role.name() + ':' + frameIndex;
    }
}
