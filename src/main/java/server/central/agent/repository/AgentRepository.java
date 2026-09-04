package server.central.agent.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import server.central.agent.entity.AgentEntity;

@Repository
/** Agent 접속 상태를 H2에 저장하는 업무용 저장소다. */
public class AgentRepository {
  private final AgentJpaRepository database;

  public AgentRepository(AgentJpaRepository database) {
    this.database = database;
  }

  public void save(AgentEntity agent) {
    database.save(agent);
  }

  public Optional<AgentEntity> find(String id) {
    return database.findById(id);
  }

  public List<AgentEntity> findAll() {
    return database.findAll().stream().sorted(Comparator.comparing(AgentEntity::agentId)).toList();
  }

  public void remove(String id) {
    database.deleteById(id);
  }

  public void deleteLastSeenBefore(Instant cutoff) {
    database.deleteByLastSeenBefore(cutoff);
  }
}

interface AgentJpaRepository extends JpaRepository<AgentEntity, String> {
  void deleteByLastSeenBefore(Instant cutoff);
}
