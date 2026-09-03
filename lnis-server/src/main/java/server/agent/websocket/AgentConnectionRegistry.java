package server.agent.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import server.model.AgentProtocol.Envelope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
/** Agent ID별 활성 WebSocket 세션을 등록하고 명령을 직렬화해 전송한다. */
public class AgentConnectionRegistry {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public AgentConnectionRegistry(ObjectMapper json) {
        this.json = json;
    }

    public void register(String agentId, WebSocketSession session) {
        // 동일 Agent가 재접속하면 이후 명령이 새 소켓으로 가도록 현재 연결을 교체한다.
        sessions.put(agentId, session);
    }

    public void remove(String agentId, WebSocketSession session) {
        sessions.remove(agentId, session);
    }

    public boolean online(String agentId) {
        return Optional.ofNullable(sessions.get(agentId))
                .map(WebSocketSession::isOpen)
                .orElse(false);
    }

    public synchronized void send(String agentId, Envelope message) {
        WebSocketSession session = sessions.get(agentId);
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("Agent is offline: " + agentId);
        }
        try {
            // Spring WebSocketSession의 동시 send를 피하려고 registry 수준에서 전송을 직렬화한다.
            session.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to send agent command", e);
        }
    }
}
