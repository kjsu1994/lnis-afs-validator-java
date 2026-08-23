package kr.co.lnis.server.config;

import kr.co.lnis.server.websocket.AgentHandshakeInterceptor;
import kr.co.lnis.server.websocket.AgentWebSocketHandler;
import kr.co.lnis.server.websocket.BrowserWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final AgentWebSocketHandler agents; private final BrowserWebSocketHandler browsers; private final AgentHandshakeInterceptor auth;
    public WebSocketConfig(AgentWebSocketHandler agents, BrowserWebSocketHandler browsers, AgentHandshakeInterceptor auth) { this.agents = agents; this.browsers = browsers; this.auth = auth; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agents, "/lnis/agent/ws").addInterceptors(auth).setAllowedOrigins("*");
        registry.addHandler(browsers, "/lnis/ws/status").setAllowedOrigins("*");
    }
}

