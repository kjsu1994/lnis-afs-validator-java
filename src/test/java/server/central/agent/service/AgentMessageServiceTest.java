package server.central.agent.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.central.agent.repository.AgentRepository;
import server.central.frameevidence.service.FrameEvidenceService;
import server.central.input.service.InputBufferService;
import server.central.realtime.service.EventService;
import server.central.session.repository.SessionRepository;
import server.central.session.service.SessionService;
import server.protocol.model.AgentProtocol.Envelope;
import server.protocol.model.AgentProtocol.EventType;
import server.protocol.model.AgentProtocol.MessageType;
import server.protocol.model.LnisModels.AgentRole;

/** Agent가 잘못된 STATUS payload를 보내더라도 WebSocket 연결 전체가 종료되지 않는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class AgentMessageServiceTest {
  @Mock private AgentRepository agents;

  @Mock private InputBufferService inputs;

  @Mock private SessionRepository sessions;

  @Mock private EventService events;

  @Mock private SessionService lifecycle;

  @Mock private FrameEvidenceService frameEvidence;

  @Test
  void statusWithoutEventTypeIsReportedAsErrorInsteadOfThrowingNullPointerException() {
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    AgentMessageService service =
        new AgentMessageService(json, agents, inputs, sessions, events, lifecycle, frameEvidence);
    UUID sessionId = UUID.randomUUID();
    Envelope malformedStatus =
        new Envelope(
            1,
            MessageType.STATUS,
            UUID.randomUUID(),
            null,
            "receiver-1",
            AgentRole.RECEIVER,
            sessionId,
            Instant.now(),
            json.valueToTree(
                Map.of(
                    "percent",
                    100,
                    "stage",
                    "RESULT",
                    "message",
                    "event type omitted",
                    "counters",
                    Map.of())));

    assertDoesNotThrow(() -> service.handle(malformedStatus));

    verify(events)
        .publish(
            eq(EventType.ERROR), eq("receiver-1"), eq(AgentRole.RECEIVER), eq(sessionId), any());
  }
}
