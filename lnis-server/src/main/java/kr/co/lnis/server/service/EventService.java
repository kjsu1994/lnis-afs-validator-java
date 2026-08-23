package kr.co.lnis.server.service;

import kr.co.lnis.common.model.AgentProtocol.BrowserEvent;
import kr.co.lnis.common.model.AgentProtocol.EventType;
import kr.co.lnis.common.model.LnisModels.AgentRole;
import kr.co.lnis.server.websocket.BrowserWebSocketHandler;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {
    private final StringRedisTemplate redis; private final BrowserWebSocketHandler browsers;
    public EventService(StringRedisTemplate redis, BrowserWebSocketHandler browsers) { this.redis = redis; this.browsers = browsers; }
    public BrowserEvent publish(EventType type, String agentId, AgentRole role, UUID sessionId, Object payload) {
        Long sequence = redis.opsForValue().increment("lnis:event:sequence"); BrowserEvent event = new BrowserEvent(sequence == null ? 0 : sequence, type, Instant.now(), agentId, role, sessionId, payload);
        String key = "lnis:events:" + (sessionId == null ? "agents" : sessionId);
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of("sequence", Long.toString(event.sequence()), "type", type.name(), "payload", String.valueOf(payload))).withStreamKey(key));
        redis.opsForStream().trim(key, 10_000); redis.expire(key, Duration.ofHours(24)); browsers.broadcast(event); return event;
    }
}

