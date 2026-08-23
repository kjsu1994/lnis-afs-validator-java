package kr.co.lnis.server.agent.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.AgentProtocol.Envelope;
import kr.co.lnis.server.agent.service.AgentMessageService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
/** 인증된 Agent WebSocket의 접속 수명주기와 메시지 검증을 담당한다. */
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private final AgentConnectionRegistry connections;
    private final AgentMessageService messages;
    private final ObjectMapper json;

    public AgentWebSocketHandler(
            AgentConnectionRegistry connections,
            AgentMessageService messages,
            ObjectMapper json) {
        this.connections = connections;
        this.messages = messages;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connections.register(agentId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws Exception {
        Envelope envelope = json.readValue(message.getPayload(), Envelope.class);
        if (!agentId(session).equals(envelope.agentId())) {
            throw new IllegalArgumentException("Agent identity mismatch");
        }
        messages.handle(envelope);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(agentId(session), session);
    }

    private static String agentId(WebSocketSession session) {
        return String.valueOf(session.getAttributes().get("agentId"));
    }
}
