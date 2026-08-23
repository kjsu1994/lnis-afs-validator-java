package kr.co.lnis.server.config;

import kr.co.lnis.server.agent.websocket.AgentHandshakeInterceptor;
import kr.co.lnis.server.agent.websocket.AgentWebSocketHandler;
import kr.co.lnis.server.realtime.websocket.BrowserWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
/** Agent 제어 채널과 브라우저 상태 채널의 URL을 등록한다. */
public class WebSocketConfig implements WebSocketConfigurer {
    private final AgentWebSocketHandler agents;
    private final BrowserWebSocketHandler browsers;
    private final AgentHandshakeInterceptor auth;

    public WebSocketConfig(
            AgentWebSocketHandler agents,
            BrowserWebSocketHandler browsers,
            AgentHandshakeInterceptor auth) {
        this.agents = agents;
        this.browsers = browsers;
        this.auth = auth;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agents, "/lnis/agent/ws").addInterceptors(auth).setAllowedOrigins("*");
        registry.addHandler(browsers, "/lnis/ws/status").setAllowedOrigins("*");
    }
}
