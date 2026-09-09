package server.central.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.CommandEndpoint;
import server.shared.model.LnisModels.AgentRole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 기존 WebSocket 경로와 새 내부 호출 경로가 서로 대상을 바꾸지 않는지 검증한다. */
class AgentConnectionRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentConnectionRegistry registry = new AgentConnectionRegistry(objectMapper);

    @Test
    void sendsDirectlyToLocalEndpoint()
    {
        CommandEndpoint endpoint = mock(CommandEndpoint.class);
        when(endpoint.online()).thenReturn(true);
        registry.registerEndpoint("sender-1", endpoint);
        Envelope message = message();
        registry.send("sender-1", message);
        verify(endpoint).send(message);
        assertTrue(registry.online("sender-1"));
    }

    @Test
    void preservesWebSocketDelivery() throws Exception
    {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        registry.register("sender-1", session);
        registry.send("sender-1", message());
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void refusesOfflineEndpointAndPreservesItsFailure()
    {
        CommandEndpoint endpoint = mock(CommandEndpoint.class);
        registry.registerEndpoint("sender-1", endpoint);
        assertThrows(IllegalStateException.class, () -> registry.send("sender-1", message()));
        verify(endpoint, never()).send(any());
        when(endpoint.online()).thenReturn(true);
        IllegalStateException rejected = new IllegalStateException("Receiver busy");
        doThrow(rejected).when(endpoint).send(any());
        assertSame(rejected, assertThrows(IllegalStateException.class,
                () -> registry.send("sender-1", message())));
    }

    @Test
    void preventsConnectionIdentityReplacement()
    {
        CommandEndpoint endpoint = mock(CommandEndpoint.class);
        when(endpoint.online()).thenReturn(true);
        registry.registerEndpoint("sender-1", endpoint);
        assertThrows(IllegalStateException.class,
                () -> registry.registerEndpoint("sender-1", mock(CommandEndpoint.class)));
        assertThrows(IllegalStateException.class,
                () -> registry.register("sender-1", mock(WebSocketSession.class)));
        registry.removeEndpoint("sender-1", mock(CommandEndpoint.class));
        assertTrue(registry.online("sender-1"));
        registry.removeEndpoint("sender-1", endpoint);
        assertFalse(registry.online("sender-1"));
    }

    private Envelope message()
    {
        return Envelope.of(MessageType.INPUT_COMPLETE, "sender-1", AgentRole.SENDER,
                null, objectMapper.createObjectNode());
    }
}
