package server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import server.model.AgentProtocol.*;
import server.model.LnisModels.AgentRole;
import server.agent.repository.AgentRepository;
import server.agent.websocket.AgentConnectionRegistry;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
/**
 * 중앙 서버의 명령과 입력 청크를 대상 Agent WebSocket으로 전달한다.
 *
 * <p>AgentRepository에서 대상 역할을 확인해 envelope에 기록하고, ConnectionRegistry를 통해 현재 활성
 * 연결로만 전송한다. 오프라인 Agent에는 명령을 대기열에 적재하지 않고 즉시 오류를 반환한다.
 */
public class AgentCommandService {
    private final AgentConnectionRegistry connections;
    private final AgentRepository agents;
    private final ObjectMapper json;

    public AgentCommandService(
            AgentConnectionRegistry connections,
            AgentRepository agents,
            ObjectMapper json) {
        this.connections = connections;
        this.agents = agents;
        this.json = json;
    }
    /** 명령 인수를 JSON tree로 변환해 COMMAND envelope로 전송하고 추적용 message ID를 반환한다. */
    public UUID command(String agentId, UUID sessionId, CommandType type, Object arguments) {
        AgentRole role = agents.find(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentId))
                .role();
        Envelope envelope = Envelope.of(
                MessageType.COMMAND,
                agentId,
                role,
                sessionId,
                json.valueToTree(new Command(type, json.valueToTree(arguments))));
        connections.send(agentId, envelope);
        return envelope.messageId();
    }
    /** GRAW 청크를 Base64로 감싸 Sender Agent의 해당 세션 입력 버퍼로 전달한다. */
    public void inputChunk(String agentId, UUID sessionId, long index, byte[] bytes) {
        AgentRole role = agents.find(agentId).orElseThrow().role();
        var payload = json.createObjectNode()
                .put("index", index)
                .put("dataBase64", java.util.Base64.getEncoder().encodeToString(bytes));
        connections.send(
                agentId,
                Envelope.of(MessageType.INPUT_CHUNK, agentId, role, sessionId, payload));
    }

    public void inputComplete(String agentId, UUID sessionId) {
        AgentRole role = agents.find(agentId).orElseThrow().role();
        connections.send(
                agentId,
                Envelope.of(
                        MessageType.INPUT_COMPLETE,
                        agentId,
                        role,
                        sessionId,
                        json.createObjectNode()));
    }
}
