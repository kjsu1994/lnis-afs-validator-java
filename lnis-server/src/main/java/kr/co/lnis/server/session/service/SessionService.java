package kr.co.lnis.server.session.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.AgentProtocol.CommandType;
import kr.co.lnis.common.model.LnisModels.*;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.agent.service.AgentCommandService;
import kr.co.lnis.server.input.service.InputBufferService;
import kr.co.lnis.server.realtime.service.EventService;
import kr.co.lnis.server.session.dto.CreateSessionRequest;
import kr.co.lnis.server.session.entity.TestSessionEntity;
import kr.co.lnis.server.session.repository.SessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
/** 단일 활성 시험을 보장하고 Receiver 준비부터 최종 판정까지 수명주기를 조정한다. */
public class SessionService {
    private static final String ACTIVE_LOCK = "lnis:lock:active-test";
    private final SessionRepository sessions; private final AgentRepository agents; private final InputBufferService inputs;
    private final AgentCommandService commands; private final EventService events; private final StringRedisTemplate redis; private final ObjectMapper json;
    public SessionService(SessionRepository sessions, AgentRepository agents, InputBufferService inputs, AgentCommandService commands,
                          EventService events, StringRedisTemplate redis, ObjectMapper json) {
        this.sessions = sessions;
        this.agents = agents;
        this.inputs = inputs;
        this.commands = commands;
        this.events = events;
        this.redis = redis;
        this.json = json;
    }

    public TestSessionEntity create(CreateSessionRequest request) {
        validate(request);
        UUID id = UUID.randomUUID();
        Boolean locked = redis.opsForValue().setIfAbsent(ACTIVE_LOCK, id.toString(), Duration.ofHours(2));
        if (!Boolean.TRUE.equals(locked)) throw new IllegalStateException("Another test session is active");
        try {
            Instant now = Instant.now();
            var session = new TestSessionEntity(
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
            commands.command(request.receiverAgentId(), id, CommandType.ARM_RECEIVER, request);
            var input = inputs.get(request.inputId());
            for (long index = 0; index < input.chunkCount(); index++) {
                commands.inputChunk(
                        request.senderAgentId(),
                        id,
                        index,
                        inputs.chunk(request.inputId(), index));
            }
            commands.inputComplete(request.senderAgentId(), id);
            commands.command(request.senderAgentId(), id, CommandType.START_SENDER, request);
            events.publish(kr.co.lnis.common.model.AgentProtocol.EventType.SESSION_STATUS, null, null, id, session);
            return session;
        } catch (Exception e) {
            redis.delete(ACTIVE_LOCK);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        }
    }

    public TestSessionEntity get(UUID id) {
        return sessions.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
    }

    public TestSessionEntity cancel(UUID id) {
        var current = get(id);
        commands.command(current.senderAgentId(), id, CommandType.CANCEL_SESSION, null);
        commands.command(current.receiverAgentId(), id, CommandType.CANCEL_SESSION, null);
        var cancelled = new TestSessionEntity(
                id,
                SessionState.CANCELLED,
                current.testType(),
                current.senderAgentId(),
                current.receiverAgentId(),
                current.inputId(),
                current.progress(),
                "사용자가 시험을 취소했습니다.",
                Verdict.INCONCLUSIVE,
                current.requestJson(),
                current.createdAt(),
                Instant.now());
        sessions.save(cancelled);
        release(id);
        events.publish(
                kr.co.lnis.common.model.AgentProtocol.EventType.SESSION_STATUS,
                null,
                null,
                id,
                cancelled);
        return cancelled;
    }

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

    public void onResult(UUID id) {
        var current = get(id);
        var tx = sessions.result(id, AgentRole.SENDER);
        var rx = sessions.result(id, AgentRole.RECEIVER);
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
        SessionState state = verdict == Verdict.INCONCLUSIVE
                ? SessionState.INCONCLUSIVE
                : SessionState.COMPLETED;
        var completed = new TestSessionEntity(
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
                kr.co.lnis.common.model.AgentProtocol.EventType.SESSION_STATUS,
                null,
                null,
                id,
                completed);
    }

    public void release(UUID id) {
        String current = redis.opsForValue().get(ACTIVE_LOCK);
        if (id.toString().equals(current)) {
            redis.delete(ACTIVE_LOCK);
        }
    }

    private void validate(CreateSessionRequest request) {
        var input = inputs.get(request.inputId());
        if (!input.complete()) {
            throw new IllegalStateException("Input upload/capture is not complete");
        }
        var sender = agents.find(request.senderAgentId()).orElseThrow(() -> new IllegalArgumentException("Sender agent not found"));
        var receiver = agents.find(request.receiverAgentId()).orElseThrow(() -> new IllegalArgumentException("Receiver agent not found"));
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
        TestOptions o = request.options();
        if ((o.testType() == TestType.TEST_B_RANDOM_ERRORS
                || o.testType() == TestType.TEST_C_BURST_ERRORS)
                && (o.errorCount() < 1 || o.errorCount() > 5880)) {
            throw new IllegalArgumentException("Test B/C error count must be 1 to 5880");
        }
        if (o.testType() == TestType.TEST_D_SYNC_RECOVERY
                && (o.errorCount() < 1
                || o.errorCount() > 68
                || o.syncDamageInterval() < 1)) {
            throw new IllegalArgumentException(
                    "Test D error count must be 1 to 68 and interval must be positive");
        }
        if (o.testType() == TestType.TEST_E_UDP_DROP
                && (o.dropRatePercent() < 0 || o.dropRatePercent() > 100)) {
            throw new IllegalArgumentException("Drop rate must be 0 to 100");
        }
    }
}
