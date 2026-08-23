package kr.co.lnis.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.AgentProtocol.*;
import kr.co.lnis.common.model.LnisModels.*;
import kr.co.lnis.server.entity.RedisEntities.AgentEntity;
import kr.co.lnis.server.repository.AgentRepository;
import kr.co.lnis.server.repository.SessionRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Base64;

@Service
public class AgentMessageService {
    private final ObjectMapper json; private final AgentRepository agents; private final InputBufferService inputs; private final SessionRepository sessions; private final EventService events; private final SessionService lifecycle;
    public AgentMessageService(ObjectMapper json, AgentRepository agents, InputBufferService inputs, SessionRepository sessions, EventService events, SessionService lifecycle) { this.json = json; this.agents = agents; this.inputs = inputs; this.sessions = sessions; this.events = events; this.lifecycle = lifecycle; }
    public void handle(Envelope envelope) throws Exception {
        switch (envelope.type()) {
            case HELLO -> { Hello hello = json.treeToValue(envelope.payload(), Hello.class); agents.save(new AgentEntity(envelope.agentId(), envelope.role(), AgentState.READY, Instant.now(), hello.agentVersion(), hello.codecAbiVersion(), hello.os(), hello.architecture(), null)); events.publish(EventType.AGENT_STATUS, envelope.agentId(), envelope.role(), null, "READY"); }
            case HEARTBEAT -> { Heartbeat heartbeat = json.treeToValue(envelope.payload(), Heartbeat.class); AgentEntity old = agents.find(envelope.agentId()).orElse(new AgentEntity(envelope.agentId(), envelope.role(), heartbeat.state(), Instant.now(), "unknown", 0, "unknown", "unknown", null)); agents.save(new AgentEntity(old.agentId(), old.role(), heartbeat.state(), Instant.now(), old.version(), old.codecAbiVersion(), old.os(), old.architecture(), old.error())); }
            case STATUS -> { Progress progress = json.treeToValue(envelope.payload(), Progress.class); events.publish(progress.type(), envelope.agentId(), envelope.role(), envelope.sessionId(), progress); }
            case INPUT_CHUNK -> { byte[] canonical = Base64.getDecoder().decode(envelope.payload().path("canonicalBase64").asText()); if (canonical.length > 0) { var input=inputs.get(envelope.sessionId()); inputs.append(envelope.sessionId(), input.chunkCount(), canonical); } }
            case PORT_LIST -> events.publish(EventType.AGENT_STATUS, envelope.agentId(), envelope.role(), null, json.treeToValue(envelope.payload(), PortList.class));
            case ROLE_RESULT -> { RoleResult result = json.treeToValue(envelope.payload(), RoleResult.class); sessions.saveResult(result); events.publish(EventType.RESULT, envelope.agentId(), envelope.role(), envelope.sessionId(), result); lifecycle.onResult(envelope.sessionId()); }
            case ERROR -> events.publish(EventType.ERROR, envelope.agentId(), envelope.role(), envelope.sessionId(), envelope.payload());
            default -> {}
        }
    }
}
