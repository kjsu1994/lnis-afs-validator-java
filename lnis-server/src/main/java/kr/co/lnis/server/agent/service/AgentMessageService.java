package kr.co.lnis.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.model.AgentProtocol.*;
import kr.co.lnis.protocol.model.LnisModels.*;
import kr.co.lnis.server.agent.entity.AgentEntity;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.input.service.InputBufferService;
import kr.co.lnis.server.frameevidence.service.FrameEvidenceService;
import kr.co.lnis.server.realtime.service.EventService;
import kr.co.lnis.server.session.repository.SessionRepository;
import kr.co.lnis.server.session.service.SessionService;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

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
    private final FrameEvidenceService frameEvidence;

    public AgentMessageService(
            ObjectMapper json,
            AgentRepository agents,
            InputBufferService inputs,
            SessionRepository sessions,
            EventService events,
            SessionService lifecycle,
            FrameEvidenceService frameEvidence) {
        this.json = json;
        this.agents = agents;
        this.inputs = inputs;
        this.sessions = sessions;
        this.events = events;
        this.lifecycle = lifecycle;
        this.frameEvidence = frameEvidence;
    }

    /** envelope 종류에 따라 Agent 상태, 입력 청크, 진행률 또는 역할 결과 처리로 분기한다. */
    public void handle(Envelope envelope) throws Exception {
        // 연결 정보(HELLO/HEARTBEAT), 입력, 진행 이벤트, 최종 결과를 각 도메인 서비스로 분배한다.
        // WebSocket Handler는 인증과 역직렬화만 담당하고 업무 상태 변경은 이 계층에서 시작된다.
        switch (envelope.type()) {
            case HELLO -> handleHello(envelope);
            case HEARTBEAT -> handleHeartbeat(envelope);
            case STATUS -> {
                Progress progress = json.treeToValue(envelope.payload(), Progress.class);
                // 구버전 또는 결함 Agent가 RoleResult를 STATUS로 잘못 보낸 경우 type이 null이 된다.
                // 이 메시지 하나 때문에 Agent WebSocket 전체가 종료되지 않도록 오류 이벤트로 격리한다.
                if (progress.type() == null) {
                    events.publish(
                            EventType.ERROR,
                            envelope.agentId(),
                            envelope.role(),
                            envelope.sessionId(),
                            java.util.Map.of(
                                    "message",
                                    "Agent STATUS event type is missing"));
                    return;
                }
                events.publish(
                        progress.type(),
                        envelope.agentId(),
                        envelope.role(),
                        envelope.sessionId(),
                        progress);
            }
            case INPUT_CHUNK -> {
                // rawSerial은 장치 진단용이며 시험 입력에는 Agent가 변환한 canonical GRAW만 누적한다.
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
            case FRAME_EVIDENCE -> frameEvidence.save(
                    envelope.sessionId(),
                    envelope.role(),
                    json.treeToValue(envelope.payload(), FrameEvidenceMessage.class));
            case ROLE_RESULT -> {
                RoleResult result = json.treeToValue(envelope.payload(), RoleResult.class);
                // 조회 API가 즉시 결과를 볼 수 있도록 저장을 먼저 끝낸 뒤 브라우저에 알린다.
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

    /** 최초 접속 정보를 Agent 조회용 JPA 엔티티로 만들고 READY 이벤트를 방송한다. */
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
                normalizeAddresses(hello.ipv4Addresses()),
                null));
        events.publish(
                EventType.AGENT_STATUS,
                envelope.agentId(),
                envelope.role(),
                null,
                "READY");
    }

    /** heartbeat는 동적 상태와 마지막 확인 시각만 갱신하고 HELLO의 장치 정보는 보존한다. */
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
                List.of(),
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
                old.ipv4Addresses(),
                old.error()));
    }

    private static List<String> normalizeAddresses(List<String> addresses) {
        return addresses == null
                ? List.of()
                : addresses.stream()
                        .filter(address -> address != null && !address.isBlank())
                        .distinct()
                        .toList();
    }
}
