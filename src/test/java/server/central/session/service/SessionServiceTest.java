package server.central.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.central.agent.entity.AgentEntity;
import server.central.agent.repository.AgentRepository;
import server.central.agent.service.AgentCommandService;
import server.central.input.entity.InputBufferEntity;
import server.central.input.service.InputBufferService;
import server.central.realtime.service.EventService;
import server.central.session.dto.CreateSessionRequest;
import server.central.session.entity.TestSessionEntity;
import server.central.session.repository.ActiveSessionLockRepository;
import server.central.session.repository.SessionRepository;
import server.protocol.model.AgentProtocol.CommandType;
import server.protocol.model.LnisModels.AfsSettings;
import server.protocol.model.LnisModels.AgentRole;
import server.protocol.model.LnisModels.SessionState;
import server.protocol.model.LnisModels.TestOptions;
import server.protocol.model.LnisModels.TestType;
import server.protocol.model.LnisModels.TransportSettings;

/** 세션 시작의 부분 실패가 Agent, H2 상태와 활성 lock에 남지 않는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {
  @Mock private SessionRepository sessions;
  @Mock private AgentRepository agents;
  @Mock private InputBufferService inputs;
  @Mock private AgentCommandService commands;
  @Mock private EventService events;
  @Mock private ActiveSessionLockRepository locks;

  private final AtomicReference<TestSessionEntity> stored = new AtomicReference<>();
  private SessionService service;
  private CreateSessionRequest request;

  @BeforeEach
  void setUp() {
    UUID inputId = UUID.randomUUID();
    InputBufferEntity input = mock(InputBufferEntity.class);
    when(input.complete()).thenReturn(true);
    when(input.chunkCount()).thenReturn(1L);
    when(inputs.get(inputId)).thenReturn(input);
    when(inputs.chunk(inputId, 0)).thenReturn(new byte[] {1, 2, 3});

    AgentEntity sender = mock(AgentEntity.class);
    AgentEntity receiver = mock(AgentEntity.class);
    when(sender.role()).thenReturn(AgentRole.SENDER);
    when(receiver.role()).thenReturn(AgentRole.RECEIVER);
    when(agents.find("sender-1")).thenReturn(Optional.of(sender));
    when(agents.find("receiver-1")).thenReturn(Optional.of(receiver));
    when(locks.tryAcquire(any(UUID.class))).thenReturn(true);

    when(sessions.find(any(UUID.class)))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));

    request =
        new CreateSessionRequest(
            "sender-1",
            "receiver-1",
            inputId,
            new AfsSettings(1),
            new TransportSettings("127.0.0.1", 45821, 45822, 3, 30, 1000, 1000),
            new TestOptions(TestType.TEST_A_NORMAL, 1, 1, 10, 0, 1, Map.of()));
    service =
        new SessionService(
            sessions,
            agents,
            inputs,
            commands,
            events,
            locks,
            new ObjectMapper().findAndRegisterModules());
  }

  @Test
  void createFailureCancelsBothAgentsMarksFailedAndReleasesLock() {
    doAnswer(
            invocation -> {
              stored.set(invocation.getArgument(0));
              return null;
            })
        .when(sessions)
        .save(any(TestSessionEntity.class));
    IllegalStateException original = new IllegalStateException("GRAW WebSocket 전송 실패");
    doThrow(original)
        .when(commands)
        .inputChunk(eq("sender-1"), any(UUID.class), eq(0L), any(byte[].class));
    // Sender 취소가 실패해도 Receiver 취소와 중앙 상태 정리를 계속해야 한다.
    doAnswer(
            invocation -> {
              if (invocation.getArgument(2) == CommandType.CANCEL_SESSION
                  && invocation.getArgument(0).equals("sender-1")) {
                throw new IllegalStateException("Sender offline");
              }
              return UUID.randomUUID();
            })
        .when(commands)
        .command(
            any(String.class),
            any(UUID.class),
            any(CommandType.class),
            nullable(Object.class));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> service.create(request));

    assertSame(original, thrown);
    ArgumentCaptor<TestSessionEntity> saved = ArgumentCaptor.forClass(TestSessionEntity.class);
    verify(sessions, org.mockito.Mockito.times(2)).save(saved.capture());
    TestSessionEntity initial = saved.getAllValues().get(0);
    TestSessionEntity failed = saved.getAllValues().get(1);
    assertEquals(SessionState.WAITING_RECEIVER, initial.state());
    assertEquals(SessionState.FAILED, failed.state());
    assertTrue(failed.message().contains("GRAW WebSocket 전송 실패"));
    assertTrue(failed.message().contains("Sender offline"));
    verify(commands)
        .command("sender-1", initial.sessionId(), CommandType.CANCEL_SESSION, null);
    verify(commands)
        .command("receiver-1", initial.sessionId(), CommandType.CANCEL_SESSION, null);
    verify(commands, never())
        .command("sender-1", initial.sessionId(), CommandType.START_SENDER, request);
    verify(locks).release(initial.sessionId());
  }

  @Test
  void compensationPersistenceFailureDoesNotHideOriginalAndStillReleasesLock() {
    IllegalStateException original = new IllegalStateException("GRAW WebSocket 전송 실패");
    IllegalStateException persistence = new IllegalStateException("FAILED 상태 저장 실패");
    doThrow(original)
        .when(commands)
        .inputChunk(eq("sender-1"), any(UUID.class), anyLong(), any(byte[].class));
    doAnswer(
            invocation -> {
              TestSessionEntity value = invocation.getArgument(0);
              if (value.state() == SessionState.FAILED) {
                throw persistence;
              }
              stored.set(value);
              return null;
            })
        .when(sessions)
        .save(any(TestSessionEntity.class));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> service.create(request));

    assertSame(original, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(persistence, thrown.getSuppressed()[0]);
    verify(commands)
        .command("sender-1", stored.get().sessionId(), CommandType.CANCEL_SESSION, null);
    verify(commands)
        .command("receiver-1", stored.get().sessionId(), CommandType.CANCEL_SESSION, null);
    verify(locks).release(stored.get().sessionId());
  }
}
