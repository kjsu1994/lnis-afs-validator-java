package server.central.session.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import server.central.agent.repository.AgentRepository;
import server.central.agent.service.AgentCommandService;
import server.central.input.service.InputBufferService;
import server.central.realtime.service.EventService;
import server.central.session.dto.CreateSessionRequest;
import server.central.session.entity.TestSessionEntity;
import server.central.session.repository.ActiveSessionLockRepository;
import server.central.session.repository.SessionRepository;
import server.protocol.model.AgentProtocol.CommandType;
import server.protocol.model.LnisModels.*;

@Service
/**
 * 단일 활성 시험을 보장하고 Receiver 준비부터 최종 판정까지 수명주기를 조정한다.
 *
 * <p>세션 생성 시 입력과 Agent 역할을 검증하고 H2 잠금 행을 획득한다. Receiver를 먼저 arm한 다음 Sender에 GRAW 청크를 전달하고 송신을 시작한다.
 * 모든 실패 경로에서는 가능한 한 lock을 해제해 다음 시험이 영구적으로 막히지 않게 한다.
 */
public class SessionService {
  private static final Logger log = LoggerFactory.getLogger(SessionService.class);
  private final SessionRepository sessions;
  private final AgentRepository agents;
  private final InputBufferService inputs;
  private final AgentCommandService commands;
  private final EventService events;
  private final ActiveSessionLockRepository locks;
  private final ObjectMapper json;

  public SessionService(
      SessionRepository sessions,
      AgentRepository agents,
      InputBufferService inputs,
      AgentCommandService commands,
      EventService events,
      ActiveSessionLockRepository locks,
      ObjectMapper json) {
    this.sessions = sessions;
    this.agents = agents;
    this.inputs = inputs;
    this.commands = commands;
    this.events = events;
    this.locks = locks;
    this.json = json;
  }

  /** 요청 검증, 단일 시험 lock 획득, Receiver 준비 및 Sender 시작을 원자적인 흐름으로 수행한다. */
  public TestSessionEntity create(CreateSessionRequest request) {
    // 1. H2 잠금을 잡기 전에 요청을 검증해야 잘못된 요청이 활성 시험 자리를 차지하지 않는다.
    validate(request);
    UUID id = UUID.randomUUID();
    if (!locks.tryAcquire(id)) throw new IllegalStateException("Another test session is active");
    try {
      // 2. 이후 어느 Agent 명령에서 실패하더라도 추적할 수 있도록 세션을 먼저 영속화한다.
      Instant now = Instant.now();
      var session =
          new TestSessionEntity(
              id,
              SessionState.WAITING_RECEIVER,
              request.options().testType(),
              request.senderAgentId(),
              request.receiverAgentId(),
              request.inputId(),
              0,
              "Receiver 준비 명령 전송",
              Verdict.INCONCLUSIVE,
              json.writeValueAsString(request),
              now,
              now);
      sessions.save(session);

      // 3. Sender가 즉시 UDP를 보내 유실되는 일을 막기 위해 Receiver 소켓을 먼저 준비시킨다.
      commands.command(request.receiverAgentId(), id, CommandType.ARM_RECEIVER, request);

      // 4. 서버 파일의 GRAW 청크를 순서대로 Sender 메모리에 모두 전달한 뒤 완료 경계를 알린다.
      var input = inputs.get(request.inputId());
      for (long index = 0; index < input.chunkCount(); index++) {
        commands.inputChunk(
            request.senderAgentId(), id, index, inputs.chunk(request.inputId(), index));
      }
      commands.inputComplete(request.senderAgentId(), id);

      // 5. 입력 전달이 끝난 후에만 실제 AFS 인코딩과 UDP 송신을 시작한다.
      commands.command(request.senderAgentId(), id, CommandType.START_SENDER, request);
      events.publish(
          server.protocol.model.AgentProtocol.EventType.SESSION_STATUS, null, null, id, session);
      return session;
    } catch (Exception e) {
      // 생성 중간 실패는 완료 이벤트가 오지 않으므로 여기서 잠금을 직접 해제한다.
      locks.release(id);
      if (e instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e);
    }
  }

  public TestSessionEntity get(UUID id) {
    return sessions
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
  }

  /** 양쪽 Agent에 취소 명령을 보내고 중앙 세션 상태와 활성 lock을 정리한다. */
  public synchronized TestSessionEntity cancel(UUID id) {
    return cancelInternal(id, "사용자가 시험을 취소했습니다.");
  }

  /**
   * 일부 Agent가 이미 오프라인이어도 나머지 Agent와 중앙 잠금 정리는 계속 수행한다. 취소 API가 한 Agent의 전송 실패 때문에 다시 409 상태를 만들지 않게
   * 하는 것이 목적이다.
   */
  private TestSessionEntity cancelInternal(UUID id, String reason) {
    var current = get(id);
    if (terminal(current.state())) {
      release(id);
      return current;
    }

    var commandErrors = new ArrayList<String>();
    cancelAgent(current.senderAgentId(), id, commandErrors);
    cancelAgent(current.receiverAgentId(), id, commandErrors);
    String message =
        commandErrors.isEmpty()
            ? reason
            : reason + " 일부 Agent 취소 명령 실패: " + String.join(", ", commandErrors);
    var cancelled =
        new TestSessionEntity(
            id,
            SessionState.CANCELLED,
            current.testType(),
            current.senderAgentId(),
            current.receiverAgentId(),
            current.inputId(),
            current.progress(),
            message,
            Verdict.INCONCLUSIVE,
            current.requestJson(),
            current.createdAt(),
            Instant.now());
    sessions.save(cancelled);
    release(id);
    events.publish(
        server.protocol.model.AgentProtocol.EventType.SESSION_STATUS, null, null, id, cancelled);
    return cancelled;
  }

  private void cancelAgent(String agentId, UUID sessionId, ArrayList<String> errors) {
    try {
      commands.command(agentId, sessionId, CommandType.CANCEL_SESSION, null);
    } catch (RuntimeException error) {
      errors.add(agentId + " (" + error.getMessage() + ")");
    }
  }

  /** H2 잠금 행이 가리키는 현재 시험을 반환하며, 손상된 잠금은 자동으로 제거한다. */
  public Optional<SessionSnapshot> activeSnapshot() {
    Optional<UUID> active = locks.current();
    if (active.isEmpty()) {
      return Optional.empty();
    }
    try {
      UUID id = active.get();
      var session = sessions.find(id);
      if (session.isEmpty() || terminal(session.get().state())) {
        release(id);
        return Optional.empty();
      }
      return Optional.of(snapshot(id));
    } catch (IllegalArgumentException error) {
      locks.release(active.get());
      return Optional.empty();
    }
  }

  /**
   * Agent 결과가 유실되거나 프로세스가 비정상 종료돼도 시험 잠금이 장시간 남지 않게 한다. 기본 30초 시험은 입력 전송 여유를 포함해 약 60초 후 중앙에서 자동
   * 취소한다.
   */
  @Scheduled(fixedDelayString = "${lnis.session-watchdog-delay-ms:5000}")
  public synchronized void cancelExpiredSession() {
    activeSnapshot()
        .ifPresent(
            snapshot -> {
              var current = get(snapshot.sessionId());
              if (Instant.now()
                  .isBefore(current.createdAt().plus(sessionMaximumDuration(current)))) {
                return;
              }
              log.warn("Automatically cancelling expired LNIS session {}", current.sessionId());
              cancelInternal(current.sessionId(), "응답 제한 시간을 초과하여 시험이 자동 취소되었습니다.");
            });
  }

  private Duration sessionMaximumDuration(TestSessionEntity session) {
    try {
      CreateSessionRequest request =
          json.readValue(session.requestJson(), CreateSessionRequest.class);
      // 대용량 GRAW를 WebSocket으로 옮기는 시간은 입력 크기에 비례해 별도 여유로 잡는다.
      long inputMegabytes =
          Math.max(1, (inputs.get(session.inputId()).receivedSize() + 1_048_575) / 1_048_576);
      long transferAllowanceSeconds = Math.min(600, Math.max(15, inputMegabytes * 2));
      // END 뒤 늦게 도착한 반복 datagram을 받는 grace와 결과 대기 시간을 모두 포함한다.
      long graceSeconds = Math.max(1, (request.transport().endGraceMilliseconds() + 999L) / 1000L);
      long totalSeconds =
          request.transport().resultTimeoutSeconds() + graceSeconds + transferAllowanceSeconds;
      return Duration.ofSeconds(Math.max(60, totalSeconds));
    } catch (Exception error) {
      log.warn(
          "Unable to calculate session timeout for {}; using fallback", session.sessionId(), error);
      return Duration.ofMinutes(2);
    }
  }

  /** H2의 세션 메타데이터와 현재 TX/RX 결과를 하나의 조회 응답으로 결합한다. */
  public SessionSnapshot snapshot(UUID id) {
    var value = get(id);
    return new SessionSnapshot(
        value.sessionId(),
        value.state(),
        value.testType(),
        value.senderAgentId(),
        value.receiverAgentId(),
        value.inputId(),
        value.progress(),
        value.message(),
        value.verdict(),
        value.createdAt(),
        value.updatedAt(),
        sessions.result(id, AgentRole.SENDER).orElse(null),
        sessions.result(id, AgentRole.RECEIVER).orElse(null));
  }

  /** TX와 RX 결과가 모두 도착한 시점에 더 보수적인 판정으로 최종 상태를 계산한다. */
  public synchronized void onResult(UUID id) {
    var current = get(id);
    if (terminal(current.state())) {
      release(id);
      return;
    }
    var tx = sessions.result(id, AgentRole.SENDER);
    var rx = sessions.result(id, AgentRole.RECEIVER);
    // ROLE_RESULT는 양쪽 WebSocket에서 독립적으로 도착하므로 첫 결과만으로 세션을 끝내지 않는다.
    if (tx.isEmpty() || rx.isEmpty()) {
      return;
    }

    Verdict verdict;
    if (tx.get().verdict() == Verdict.FAIL || rx.get().verdict() == Verdict.FAIL) {
      verdict = Verdict.FAIL;
    } else if (tx.get().verdict() == Verdict.INCONCLUSIVE
        || rx.get().verdict() == Verdict.INCONCLUSIVE) {
      verdict = Verdict.INCONCLUSIVE;
    } else {
      verdict = Verdict.PASS;
    }
    SessionState state =
        verdict == Verdict.INCONCLUSIVE ? SessionState.INCONCLUSIVE : SessionState.COMPLETED;
    var completed =
        new TestSessionEntity(
            id,
            state,
            current.testType(),
            current.senderAgentId(),
            current.receiverAgentId(),
            current.inputId(),
            100,
            "시험 결과 수집 완료",
            verdict,
            current.requestJson(),
            current.createdAt(),
            Instant.now());
    sessions.save(completed);
    release(id);
    events.publish(
        server.protocol.model.AgentProtocol.EventType.SESSION_STATUS, null, null, id, completed);
  }

  public void release(UUID id) {
    // 늦게 도착한 이전 결과가 새 세션 잠금을 지우지 않도록 저장소에서 소유 ID를 비교한다.
    locks.release(id);
  }

  private static boolean terminal(SessionState state) {
    return state == SessionState.COMPLETED
        || state == SessionState.CANCELLED
        || state == SessionState.FAILED
        || state == SessionState.INCONCLUSIVE;
  }

  /** 입력 완료 여부, Agent 역할, 포트, 반복 횟수와 시험별 오류 범위를 검증한다. */
  private void validate(CreateSessionRequest request) {
    var input = inputs.get(request.inputId());
    if (!input.complete()) {
      throw new IllegalStateException("Input upload/capture is not complete");
    }
    var sender =
        agents
            .find(request.senderAgentId())
            .orElseThrow(() -> new IllegalArgumentException("Sender agent not found"));
    var receiver =
        agents
            .find(request.receiverAgentId())
            .orElseThrow(() -> new IllegalArgumentException("Receiver agent not found"));
    if (sender.role() != AgentRole.SENDER || receiver.role() != AgentRole.RECEIVER) {
      throw new IllegalArgumentException("Agent role mismatch");
    }
    TransportSettings t = request.transport();
    if (t.dataPort() < 1
        || t.dataPort() > 65535
        || t.resultPort() < 1
        || t.resultPort() > 65535
        || t.dataPort() == t.resultPort()) {
      throw new IllegalArgumentException(
          "Data and result ports must be different values from 1 to 65535");
    }
    if (t.repeatCount() < 1 || t.repeatCount() > 20) {
      throw new IllegalArgumentException("Repeat count must be 1 to 20");
    }
    if (request.afs().prn() < 1 || request.afs().prn() > 8) {
      throw new IllegalArgumentException("AFS PRN must be 1 to 8");
    }
    TestOptions o = request.options();
    if ((o.testType() == TestType.TEST_B_RANDOM_ERRORS
            || o.testType() == TestType.TEST_C_BURST_ERRORS)
        && (o.errorCount() < 1 || o.errorCount() > 5880)) {
      throw new IllegalArgumentException("Test B/C error count must be 1 to 5880");
    }
    if (o.testType() == TestType.TEST_D_SYNC_RECOVERY
        && (o.errorCount() < 1 || o.errorCount() > 68 || o.syncDamageInterval() < 1)) {
      throw new IllegalArgumentException(
          "Test D error count must be 1 to 68 and interval must be positive");
    }
    if (o.testType() == TestType.TEST_E_UDP_DROP
        && (o.dropRatePercent() < 0 || o.dropRatePercent() > 100)) {
      throw new IllegalArgumentException("Drop rate must be 0 to 100");
    }
  }
}
