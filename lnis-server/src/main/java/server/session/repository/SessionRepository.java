package server.session.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import server.model.LnisModels.AgentRole;
import server.model.LnisModels.RoleResult;
import server.model.LnisModels.SessionState;
import server.session.entity.RoleResultEntity;
import server.session.entity.TestSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
/** 시험 세션과 역할별 결과를 H2 테이블에 보관한다. */
public class SessionRepository {
  private final SessionJpaRepository sessions;
  private final RoleResultJpaRepository results;
  private final ObjectMapper json;

  public SessionRepository(
      SessionJpaRepository sessions, RoleResultJpaRepository results, ObjectMapper json) {
    this.sessions = sessions;
    this.results = results;
    this.json = json;
  }

  public void save(TestSessionEntity value) {
    sessions.save(value);
  }

  public Optional<TestSessionEntity> find(UUID id) {
    return sessions.findById(id);
  }

  public void saveResult(RoleResult result) {
    try {
      results.save(
          new RoleResultEntity(
              result.sessionId(), result.role(), json.writeValueAsString(result), Instant.now()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Optional<RoleResult> result(UUID id, AgentRole role) {
    return results
        .findBySessionIdAndRole(id, role)
        .map(
            value -> {
              try {
                return json.readValue(value.getResultJson(), RoleResult.class);
              } catch (Exception error) {
                throw new IllegalStateException(error);
              }
            });
  }

  public boolean existsByInputId(UUID inputId) {
    return sessions.existsByInputId(inputId);
  }

  public List<UUID> terminalBefore(Instant cutoff) {
    return sessions
        .findByStateInAndUpdatedAtBefore(
            List.of(
                SessionState.COMPLETED, SessionState.CANCELLED,
                SessionState.FAILED, SessionState.INCONCLUSIVE),
            cutoff)
        .stream()
        .map(TestSessionEntity::sessionId)
        .toList();
  }

  public void delete(UUID id) {
    results.deleteBySessionId(id);
    sessions.deleteById(id);
  }
}

interface SessionJpaRepository extends JpaRepository<TestSessionEntity, UUID> {
  boolean existsByInputId(UUID inputId);

  List<TestSessionEntity> findByStateInAndUpdatedAtBefore(
      List<SessionState> states, Instant cutoff);
}

interface RoleResultJpaRepository extends JpaRepository<RoleResultEntity, RoleResultEntity.Key> {
  Optional<RoleResultEntity> findBySessionIdAndRole(UUID sessionId, AgentRole role);

  void deleteBySessionId(UUID sessionId);
}
