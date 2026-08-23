package kr.co.lnis.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.AgentProtocol.*;
import kr.co.lnis.common.model.LnisModels.AgentRole;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.agent.websocket.AgentConnectionRegistry;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
/** 중앙 서버의 명령과 입력 청크를 대상 Agent WebSocket으로 전달한다. */
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
