package kr.co.lnis.server.agent.service;

import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.LnisModels.AgentState;
import kr.co.lnis.server.agent.entity.AgentEntity;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.realtime.service.EventService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;

@Service
/** Heartbeat가 끊긴 Agent를 OFFLINE으로 전환하고 상태 이벤트를 발행한다. */
public class AgentHealthService {
    private final AgentRepository agents;
    private final EventService events;

    public AgentHealthService(AgentRepository agents, EventService events) {
        this.agents = agents;
        this.events = events;
    }

    @Scheduled(fixedDelay=5000)
    public void markStaleAgentsOffline() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(15));
        for (AgentEntity agent : agents.findAll()) {
            if (agent.state() == AgentState.OFFLINE || !agent.lastSeen().isBefore(cutoff)) {
                continue;
            }
            var offline = new AgentEntity(
                    agent.agentId(),
                    agent.role(),
                    AgentState.OFFLINE,
                    agent.lastSeen(),
                    agent.version(),
                    agent.codecAbiVersion(),
                    agent.os(),
                    agent.architecture(),
                    "Heartbeat timeout");
            agents.save(offline);
            events.publish(
                    EventType.AGENT_STATUS,
                    agent.agentId(),
                    agent.role(),
                    null,
                    offline);
        }
    }
}
