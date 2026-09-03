package kr.co.lnis.server.frameevidence.repository;

import java.time.Instant;
import java.util.*;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.frameevidence.entity.FrameEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Sender/Receiver 프레임 증거를 H2 BLOB으로 저장한다. */
@Repository
public class FrameEvidenceRepository {
  private final FrameEvidenceJpaRepository database;

  public FrameEvidenceRepository(FrameEvidenceJpaRepository database) {
    this.database = database;
  }

  @Transactional
  public synchronized void save(UUID sessionId, AgentRole role, FrameEvidenceMessage evidence) {
    Instant now = Instant.now();
    var current =
        database.findBySessionIdAndRoleAndFrameIndex(sessionId, role, evidence.frameIndex());
    if (current.isPresent()) {
      current.get().replace(evidence, now);
    } else {
      database.save(new FrameEvidenceEntity(sessionId, role, evidence.frameIndex(), evidence, now));
    }
  }

  public Optional<FrameEvidenceEntity> find(UUID sessionId, AgentRole role, int frameIndex) {
    return database.findBySessionIdAndRoleAndFrameIndex(sessionId, role, frameIndex);
  }

  public List<FrameEvidenceEntity> findAll(UUID sessionId) {
    return database.findBySessionId(sessionId);
  }

  public void deleteBySessionId(UUID sessionId) {
    database.deleteBySessionId(sessionId);
  }
}

interface FrameEvidenceJpaRepository extends JpaRepository<FrameEvidenceEntity, Long> {
  Optional<FrameEvidenceEntity> findBySessionIdAndRoleAndFrameIndex(
      UUID sessionId, AgentRole role, int frameIndex);

  List<FrameEvidenceEntity> findBySessionId(UUID sessionId);

  void deleteBySessionId(UUID sessionId);
}
