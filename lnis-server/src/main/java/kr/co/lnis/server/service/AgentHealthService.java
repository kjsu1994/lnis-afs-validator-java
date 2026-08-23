package kr.co.lnis.server.service;

import kr.co.lnis.common.model.AgentProtocol.EventType;
import kr.co.lnis.common.model.LnisModels.AgentState;
import kr.co.lnis.server.entity.RedisEntities.AgentEntity;
import kr.co.lnis.server.repository.AgentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;

@Service
public class AgentHealthService {
    private final AgentRepository agents; private final EventService events;
    public AgentHealthService(AgentRepository agents,EventService events){this.agents=agents;this.events=events;}
    @Scheduled(fixedDelay=5000)
    public void markStaleAgentsOffline(){Instant cutoff=Instant.now().minus(Duration.ofSeconds(15));for(AgentEntity agent:agents.findAll())if(agent.state()!=AgentState.OFFLINE&&agent.lastSeen().isBefore(cutoff)){var offline=new AgentEntity(agent.agentId(),agent.role(),AgentState.OFFLINE,agent.lastSeen(),agent.version(),agent.codecAbiVersion(),agent.os(),agent.architecture(),"Heartbeat timeout");agents.save(offline);events.publish(EventType.AGENT_STATUS,agent.agentId(),agent.role(),null,offline);}}
}
