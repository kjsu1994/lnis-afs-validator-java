package kr.co.lnis.server.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.*;

@Component
public class AgentHandshakeInterceptor implements HandshakeInterceptor {
    private final Map<String,String> tokens;
    public AgentHandshakeInterceptor(@Value("${lnis.agent-tokens:sender-1=change-me-sender,receiver-1=change-me-receiver}") String configured) {
        Map<String,String> parsed = new HashMap<>(); for (String item : configured.split(",")) { String[] pair = item.trim().split("=", 2); if (pair.length == 2) parsed.put(pair[0], pair[1]); } tokens = Map.copyOf(parsed);
    }
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String,Object> attributes) {
        String id = request.getHeaders().getFirst("X-LNIS-Agent-Id"); String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (id == null || authorization == null || !authorization.equals("Bearer " + tokens.get(id))) return false;
        attributes.put("agentId", id); return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
}

