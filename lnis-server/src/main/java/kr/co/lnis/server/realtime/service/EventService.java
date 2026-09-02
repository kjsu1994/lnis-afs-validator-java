package kr.co.lnis.server.realtime.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kr.co.lnis.protocol.model.AgentProtocol.BrowserEvent;
import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.realtime.websocket.BrowserWebSocketHandler;
import org.springframework.stereotype.Service;

@Service
/**
 * 상태 이벤트를 연결된 브라우저에 즉시 방송한다.
 *
 * <p>전역 증가 sequence를 부여해 브라우저가 이벤트 순서를 판단할 수 있게 한다. 세션별 Stream은 최대 이벤트 이력은 저장하지 않으며 sequence는 서버
 * 프로세스 안에서만 증가한다.
 */
public class EventService {
  private final AtomicLong sequence = new AtomicLong();
  private final BrowserWebSocketHandler browsers;

  public EventService(BrowserWebSocketHandler browsers) {
    this.browsers = browsers;
  }

  /** 이벤트를 현재 연결된 모든 브라우저 구독자에게 전송한다. */
  public BrowserEvent publish(
      EventType type, String agentId, AgentRole role, UUID sessionId, Object payload) {
    // 모든 세션이 공유하는 sequence라 서로 다른 Agent 이벤트도 발생 순서대로 정렬할 수 있다.
    BrowserEvent event =
        new BrowserEvent(
            sequence.incrementAndGet(), type, Instant.now(), agentId, role, sessionId, payload);
    browsers.broadcast(event);
    return event;
  }
}
