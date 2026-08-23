package kr.co.lnis.server.frameevidence.entity;

import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import java.time.Instant;
import java.util.UUID;

/** Redis에 저장되는 역할별 AFS 프레임 증거 원문이다. */
public record FrameEvidenceEntity(
        UUID sessionId,
        AgentRole role,
        int frameIndex,
        FrameEvidenceMessage evidence,
        Instant receivedAt) {
}
