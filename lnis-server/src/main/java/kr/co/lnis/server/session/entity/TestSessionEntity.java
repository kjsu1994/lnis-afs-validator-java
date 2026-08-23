package kr.co.lnis.server.session.entity;

import java.time.Instant;
import java.util.UUID;
import kr.co.lnis.protocol.model.LnisModels.SessionState;
import kr.co.lnis.protocol.model.LnisModels.TestType;
import kr.co.lnis.protocol.model.LnisModels.Verdict;

/** Redis에 저장되는 시험 세션의 현재 상태와 요청 원문이다. */
public record TestSessionEntity(
        UUID sessionId,
        SessionState state,
        TestType testType,
        String senderAgentId,
        String receiverAgentId,
        UUID inputId,
        int progress,
        String message,
        Verdict verdict,
        String requestJson,
        Instant createdAt,
        Instant updatedAt) {}
