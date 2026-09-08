package server.central.frameevidence;

import lombok.RequiredArgsConstructor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import server.shared.model.AgentProtocol.FrameEvidenceMessage;
import server.shared.model.LnisModels.AgentRole;

import java.time.Instant;
import java.util.*;
import java.util.Optional;

/** Sender/Receiver 프레임 증거를 H2 BLOB으로 저장한다. */
@RequiredArgsConstructor
@Repository
public class FrameEvidenceRepository {
    private final FrameEvidenceJpaRepository frameEvidenceJpaRepository;

    @Transactional
    public synchronized void save(UUID sessionId, AgentRole role, FrameEvidenceMessage evidence)
    {
        Instant now = Instant.now();
        Optional<FrameEvidenceEntity> current =
                frameEvidenceJpaRepository.findBySessionIdAndRoleAndFrameIndex(
                        sessionId, role, evidence.frameIndex());
        if (current.isPresent()) {
            current.get().replace(evidence, now);
        } else {
            frameEvidenceJpaRepository.save(
                    new FrameEvidenceEntity(sessionId, role, evidence.frameIndex(), evidence, now));
        }
    }

    public Optional<FrameEvidenceEntity> find(UUID sessionId, AgentRole role, int frameIndex)
    {
        return frameEvidenceJpaRepository.findBySessionIdAndRoleAndFrameIndex(
                sessionId, role, frameIndex);
    }

    public List<FrameEvidenceEntity> findAll(UUID sessionId)
    {
        return frameEvidenceJpaRepository.findBySessionId(sessionId);
    }

    public void deleteBySessionId(UUID sessionId)
    {
        frameEvidenceJpaRepository.deleteBySessionId(sessionId);
    }
}

interface FrameEvidenceJpaRepository extends JpaRepository<FrameEvidenceEntity, Long> {
    Optional<FrameEvidenceEntity> findBySessionIdAndRoleAndFrameIndex(
            UUID sessionId, AgentRole role, int frameIndex);

    List<FrameEvidenceEntity> findBySessionId(UUID sessionId);

    void deleteBySessionId(UUID sessionId);
}

