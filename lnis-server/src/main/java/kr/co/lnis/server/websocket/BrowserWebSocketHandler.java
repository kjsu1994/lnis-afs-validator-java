package kr.co.lnis.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BrowserWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet(); private final ObjectMapper json;
    public BrowserWebSocketHandler(ObjectMapper json) { this.json = json; }
    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }
    public void broadcast(Object event) { try { String body = json.writeValueAsString(event); for (WebSocketSession session : sessions) if (session.isOpen()) synchronized (session) { session.sendMessage(new TextMessage(body)); } } catch (Exception ignored) {} }
}

