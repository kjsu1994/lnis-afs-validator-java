package server.central.agent;

import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.central.realtime.EventService;
import server.shared.model.AgentProtocol.EventType;
import server.shared.model.LnisModels.AgentState;

@Service
/** Heartbeat가 끊긴 Agent를 OFFLINE으로 전환하고 상태 이벤트를 발행한다. */
public class AgentHealthService {
  private final AgentRepository agents;
  private final EventService events;

  public AgentHealthService(AgentRepository agents, EventService events) {
    this.agents = agents;
    this.events = events;
  }

  @Scheduled(fixedDelay = 5000)
  public void markStaleAgentsOffline() {
    Instant cutoff = Instant.now().minus(Duration.ofSeconds(15));
    for (AgentEntity agent : agents.findAll()) {
      if (agent.state() == AgentState.OFFLINE || !agent.lastSeen().isBefore(cutoff)) {
        continue;
      }
      var offline =
          new AgentEntity(
              agent.agentId(),
              agent.role(),
              AgentState.OFFLINE,
              agent.lastSeen(),
              agent.version(),
              agent.codecAbiVersion(),
              agent.os(),
              agent.architecture(),
              agent.ipv4Addresses(),
              "Heartbeat timeout");
      agents.save(offline);
      events.publish(EventType.AGENT_STATUS, agent.agentId(), agent.role(), null, offline);
    }
  }
}
