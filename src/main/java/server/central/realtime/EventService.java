package server.central.realtime;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import server.shared.model.AgentProtocol.BrowserEvent;
import server.shared.model.AgentProtocol.EventType;
import server.shared.model.LnisModels.AgentRole;

import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
@Service
/**
 * 상태 이벤트를 연결된 브라우저에 즉시 방송한다.
 *
 * <p>기준 버전처럼 전역 증가 sequence와 세션별 최근 이벤트를 먼저 영속화한 뒤 브라우저에 방송한다.
 */
public class EventService {
    private final RealtimeEventRepository realtimeEventRepository;
    private final BrowserWebSocketHandler browserWebSocketHandler;

    /** 이벤트를 현재 연결된 모든 브라우저 구독자에게 전송한다. */
    public BrowserEvent publish(
            EventType type, String agentId, AgentRole role, UUID sessionId, Object payload)
    {
        // 모든 세션이 공유하는 sequence라 서로 다른 Agent 이벤트도 발생 순서대로 정렬할 수 있다.
        Instant createdAt = Instant.now();
        String streamKey = sessionId == null ? "agents" : sessionId.toString();
        long sequence =
                realtimeEventRepository.append(
                        streamKey,
                        type,
                        agentId,
                        role,
                        sessionId,
                        String.valueOf(payload),
                        createdAt);
        BrowserEvent event =
                new BrowserEvent(sequence, type, createdAt, agentId, role, sessionId, payload);
        browserWebSocketHandler.broadcast(event);
        return event;
    }
}

