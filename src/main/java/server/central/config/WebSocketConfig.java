package server.central.config;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import server.central.agent.AgentHandshakeInterceptor;
import server.central.agent.AgentWebSocketHandler;
import server.central.realtime.BrowserWebSocketHandler;

@RequiredArgsConstructor
@Configuration
@EnableWebSocket
/** Agent 제어 채널과 브라우저 상태 채널의 URL을 등록한다. */
public class WebSocketConfig implements WebSocketConfigurer {
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final BrowserWebSocketHandler browserWebSocketHandler;
    private final AgentHandshakeInterceptor auth;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        registry.addHandler(agentWebSocketHandler, "/lnis/agent/ws")
                .addInterceptors(auth)
                .setAllowedOrigins("*");
        registry.addHandler(browserWebSocketHandler, "/lnis/ws/status").setAllowedOrigins("*");
    }
}

