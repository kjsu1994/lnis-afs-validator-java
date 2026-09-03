package server.realtime.service;

import java.time.Instant;
import java.util.UUID;
import server.model.AgentProtocol.BrowserEvent;
import server.model.AgentProtocol.EventType;
import server.model.LnisModels.AgentRole;
import server.realtime.repository.RealtimeEventRepository;
import server.realtime.websocket.BrowserWebSocketHandler;
import org.springframework.stereotype.Service;

@Service
/**
 * 상태 이벤트를 연결된 브라우저에 즉시 방송한다.
 *
 * <p>기준 버전처럼 전역 증가 sequence와 세션별 최근 이벤트를 먼저 영속화한 뒤 브라우저에 방송한다.
 */
public class EventService {
  private final RealtimeEventRepository events;
  private final BrowserWebSocketHandler browsers;

  public EventService(RealtimeEventRepository events, BrowserWebSocketHandler browsers) {
    this.events = events;
    this.browsers = browsers;
  }

  /** 이벤트를 현재 연결된 모든 브라우저 구독자에게 전송한다. */
  public BrowserEvent publish(
      EventType type, String agentId, AgentRole role, UUID sessionId, Object payload) {
    // 모든 세션이 공유하는 sequence라 서로 다른 Agent 이벤트도 발생 순서대로 정렬할 수 있다.
    Instant createdAt = Instant.now();
    String streamKey = sessionId == null ? "agents" : sessionId.toString();
    long sequence =
        events.append(
            streamKey, type, agentId, role, sessionId, String.valueOf(payload), createdAt);
    BrowserEvent event =
        new BrowserEvent(sequence, type, createdAt, agentId, role, sessionId, payload);
    browsers.broadcast(event);
    return event;
  }
}
