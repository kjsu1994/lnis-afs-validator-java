package server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import server.model.AgentProtocol.Envelope;
import server.model.AgentProtocol.EventType;
import server.model.AgentProtocol.MessageType;
import server.model.LnisModels.AgentRole;
import server.agent.repository.AgentRepository;
import server.input.service.InputBufferService;
import server.frameevidence.service.FrameEvidenceService;
import server.realtime.service.EventService;
import server.session.repository.SessionRepository;
import server.session.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** Agent가 잘못된 STATUS payload를 보내더라도 WebSocket 연결 전체가 종료되지 않는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class AgentMessageServiceTest {
    @Mock
    private AgentRepository agents;

    @Mock
    private InputBufferService inputs;

    @Mock
    private SessionRepository sessions;

    @Mock
    private EventService events;

    @Mock
    private SessionService lifecycle;

    @Mock
    private FrameEvidenceService frameEvidence;

    @Test
    void statusWithoutEventTypeIsReportedAsErrorInsteadOfThrowingNullPointerException() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentMessageService service = new AgentMessageService(
                json,
                agents,
                inputs,
                sessions,
                events,
                lifecycle,
                frameEvidence);
        UUID sessionId = UUID.randomUUID();
        Envelope malformedStatus = new Envelope(
                1,
                MessageType.STATUS,
                UUID.randomUUID(),
                null,
                "receiver-1",
                AgentRole.RECEIVER,
                sessionId,
                Instant.now(),
                json.valueToTree(Map.of(
                        "percent", 100,
                        "stage", "RESULT",
                        "message", "event type omitted",
                        "counters", Map.of())));

        assertDoesNotThrow(() -> service.handle(malformedStatus));

        verify(events).publish(
                eq(EventType.ERROR),
                eq("receiver-1"),
                eq(AgentRole.RECEIVER),
                eq(sessionId),
                any());
    }
}
