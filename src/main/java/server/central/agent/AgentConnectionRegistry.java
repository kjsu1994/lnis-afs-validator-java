package server.central.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.CommandEndpoint;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Component
/** Agent ID별 활성 WebSocket 세션을 등록하고 명령을 직렬화해 전송한다. */
public class AgentConnectionRegistry {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CommandEndpoint> endpoints = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public synchronized void register(String agentId, WebSocketSession session)
    {
        if (endpoints.containsKey(agentId)) {
            throw new IllegalStateException("내부 실행기로 등록된 ID는 WebSocket으로 사용할 수 없습니다: " + agentId);
        }
        // 동일 Agent가 재접속하면 이후 명령이 새 소켓으로 가도록 현재 연결을 교체한다.
        sessions.put(agentId, session);
    }

    public void remove(String agentId, WebSocketSession session)
    {
        sessions.remove(agentId, session);
    }

    /** 노드 조립 계층에서 등록한다. 같은 ID의 실행기를 조용히 교체하지 않는다. */
    public synchronized void registerEndpoint(String agentId, CommandEndpoint endpoint)
    {
        java.util.Objects.requireNonNull(endpoint, "endpoint");
        if (sessions.containsKey(agentId) || endpoints.putIfAbsent(agentId, endpoint) != null) {
            throw new IllegalStateException("이미 등록된 실행기 ID입니다: " + agentId);
        }
    }

    public void removeEndpoint(String agentId, CommandEndpoint endpoint)
    {
        // 이전 인스턴스의 종료가 새 인스턴스 등록을 지우지 않게 값까지 비교한다.
        endpoints.remove(agentId, endpoint);
    }

    public boolean online(String agentId)
    {
        CommandEndpoint endpoint = endpoints.get(agentId);
        if (endpoint != null) {
            return endpoint.online();
        }
        return Optional.ofNullable(sessions.get(agentId))
                .map(WebSocketSession::isOpen)
                .orElse(false);
    }

    public synchronized void send(String agentId, Envelope message)
    {
        CommandEndpoint endpoint = endpoints.get(agentId);
        if (endpoint != null) {
            if (!endpoint.online()) {
                throw new IllegalStateException("Agent is offline: " + agentId);
            }
            endpoint.send(message);
            return;
        }
        WebSocketSession session = sessions.get(agentId);
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("Agent is offline: " + agentId);
        }
        try {
            // Spring WebSocketSession의 동시 send를 피하려고 registry 수준에서 전송을 직렬화한다.
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to send agent command", e);
        }
    }
}
