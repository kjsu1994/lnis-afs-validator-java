package kr.co.lnis.server.realtime.service;

import kr.co.lnis.protocol.model.AgentProtocol.BrowserEvent;
import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.realtime.websocket.BrowserWebSocketHandler;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
/**
 * 상태 이벤트를 Redis Stream에 기록하고 연결된 브라우저에 방송한다.
 *
 * <p>전역 증가 sequence를 부여해 브라우저가 이벤트 순서를 판단할 수 있게 한다. 세션별 Stream은 최대
 * 10,000개와 24시간 TTL로 제한해 장시간 실행 시 Redis 메모리가 무제한 증가하지 않게 한다.
 */
public class EventService {
    private final StringRedisTemplate redis;
    private final BrowserWebSocketHandler browsers;

    public EventService(StringRedisTemplate redis, BrowserWebSocketHandler browsers) {
        this.redis = redis;
        this.browsers = browsers;
    }

    /** 이벤트를 Redis에 먼저 기록한 뒤 현재 연결된 모든 브라우저 구독자에게 전송한다. */
    public BrowserEvent publish(EventType type, String agentId, AgentRole role, UUID sessionId, Object payload) {
        Long sequence = redis.opsForValue().increment("lnis:event:sequence");
        BrowserEvent event = new BrowserEvent(
                sequence == null ? 0 : sequence,
                type,
                Instant.now(),
                agentId,
                role,
                sessionId,
                payload);
        String key = "lnis:events:" + (sessionId == null ? "agents" : sessionId);
        Map<String, String> fields = Map.of(
                "sequence", Long.toString(event.sequence()),
                "type", type.name(),
                "payload", String.valueOf(payload));
        redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(key));
        redis.opsForStream().trim(key, 10_000);
        redis.expire(key, Duration.ofHours(24));
        browsers.broadcast(event);
        return event;
    }
}
