package kr.co.lnis.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.AgentProtocol.Envelope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentConnectionRegistry {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>(); private final ObjectMapper json;
    public AgentConnectionRegistry(ObjectMapper json) { this.json = json; }
    public void register(String agentId, WebSocketSession session) { sessions.put(agentId, session); }
    public void remove(String agentId, WebSocketSession session) { sessions.remove(agentId, session); }
    public boolean online(String agentId) { return Optional.ofNullable(sessions.get(agentId)).map(WebSocketSession::isOpen).orElse(false); }
    public synchronized void send(String agentId, Envelope message) {
        WebSocketSession session = sessions.get(agentId); if (session == null || !session.isOpen()) throw new IllegalStateException("Agent is offline: " + agentId);
        try { session.sendMessage(new TextMessage(json.writeValueAsString(message))); } catch (Exception e) { throw new IllegalStateException("Unable to send agent command", e); }
    }
}

