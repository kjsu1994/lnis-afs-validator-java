package kr.co.lnis.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.model.AgentProtocol.*;
import kr.co.lnis.protocol.model.LnisModels.*;
import kr.co.lnis.server.agent.entity.AgentEntity;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.input.service.InputBufferService;
import kr.co.lnis.server.realtime.service.EventService;
import kr.co.lnis.server.session.repository.SessionRepository;
import kr.co.lnis.server.session.service.SessionService;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Base64;

@Service
/**
 * Agent protocol 메시지를 기능별 저장소와 브라우저 이벤트로 연결한다.
 *
 * <p>이 클래스가 Agent WebSocket transport와 input/session/realtime 도메인 사이의 경계다.
 * 메시지 종류별 payload 타입을 여기서 확정하고, Agent가 보낸 임의 JSON이
 * Repository까지 직접 전달되지 않게 한다.
 */
public class AgentMessageService {
    private final ObjectMapper json;
    private final AgentRepository agents;
    private final InputBufferService inputs;
    private final SessionRepository sessions;
    private final EventService events;
    private final SessionService lifecycle;

    public AgentMessageService(
            ObjectMapper json,
            AgentRepository agents,
            InputBufferService inputs,
            SessionRepository sessions,
            EventService events,
            SessionService lifecycle) {
        this.json = json;
        this.agents = agents;
        this.inputs = inputs;
        this.sessions = sessions;
        this.events = events;
        this.lifecycle = lifecycle;
    }

    /** envelope 종류에 따라 Agent 상태, 입력 청크, 진행률 또는 역할 결과 처리로 분기한다. */
    public void handle(Envelope envelope) throws Exception {
        switch (envelope.type()) {
            case HELLO -> handleHello(envelope);
            case HEARTBEAT -> handleHeartbeat(envelope);
            case STATUS -> {
                Progress progress = json.treeToValue(envelope.payload(), Progress.class);
                events.publish(
                        progress.type(),
                        envelope.agentId(),
                        envelope.role(),
                        envelope.sessionId(),
                        progress);
            }
            case INPUT_CHUNK -> {
                byte[] canonical = Base64.getDecoder().decode(
                        envelope.payload().path("canonicalBase64").asText());
                if (canonical.length > 0) {
                    var input = inputs.get(envelope.sessionId());
                    inputs.append(envelope.sessionId(), input.chunkCount(), canonical);
                }
            }
            case PORT_LIST -> events.publish(
                    EventType.AGENT_STATUS,
                    envelope.agentId(),
                    envelope.role(),
                    null,
                    json.treeToValue(envelope.payload(), PortList.class));
            case ROLE_RESULT -> {
                RoleResult result = json.treeToValue(envelope.payload(), RoleResult.class);
                sessions.saveResult(result);
                events.publish(
                        EventType.RESULT,
                        envelope.agentId(),
                        envelope.role(),
                        envelope.sessionId(),
                        result);
                lifecycle.onResult(envelope.sessionId());
            }
            case ERROR -> events.publish(
                    EventType.ERROR,
                    envelope.agentId(),
                    envelope.role(),
                    envelope.sessionId(),
                    envelope.payload());
            default -> {}
        }
    }

    private void handleHello(Envelope envelope) throws Exception {
        Hello hello = json.treeToValue(envelope.payload(), Hello.class);
        agents.save(new AgentEntity(
                envelope.agentId(),
                envelope.role(),
                AgentState.READY,
                Instant.now(),
                hello.agentVersion(),
                hello.codecAbiVersion(),
                hello.os(),
                hello.architecture(),
                null));
        events.publish(
                EventType.AGENT_STATUS,
                envelope.agentId(),
                envelope.role(),
                null,
                "READY");
    }

    private void handleHeartbeat(Envelope envelope) throws Exception {
        Heartbeat heartbeat = json.treeToValue(envelope.payload(), Heartbeat.class);
        AgentEntity old = agents.find(envelope.agentId()).orElse(new AgentEntity(
                envelope.agentId(),
                envelope.role(),
                heartbeat.state(),
                Instant.now(),
                "unknown",
                0,
                "unknown",
                "unknown",
                null));
        agents.save(new AgentEntity(
                old.agentId(),
                old.role(),
                heartbeat.state(),
                Instant.now(),
                old.version(),
                old.codecAbiVersion(),
                old.os(),
                old.architecture(),
                old.error()));
    }
}
