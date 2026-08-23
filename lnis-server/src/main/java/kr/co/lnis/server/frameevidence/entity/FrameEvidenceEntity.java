package kr.co.lnis.server.frameevidence.entity;

import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import java.time.Instant;
import java.util.UUID;

/** Sender 또는 Receiver가 제출한 단일 AFS 프레임 증거를 Redis에 저장하는 엔티티다. */
public record FrameEvidenceEntity(
        /** 프레임 증거가 속한 시험 세션 UUID다. */
        UUID sessionId,
        /** 증거를 제출한 Agent 역할이며 같은 프레임에 Sender/Receiver 엔티티가 각각 존재할 수 있다. */
        AgentRole role,
        /** 세션 내부의 0부터 시작하는 논리 AFS 프레임 번호다. */
        int frameIndex,
        /** 6,000비트 원문, CRC 진단값과 오류 위치를 포함한 Agent 전달 객체다. */
        FrameEvidenceMessage evidence,
        /** 서버가 해당 WebSocket 증거 메시지를 수신한 UTC 시각이다. */
        Instant receivedAt) {
}
