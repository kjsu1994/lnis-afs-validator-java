package kr.co.lnis.server.entity;

import java.time.Instant;
import java.util.UUID;
import kr.co.lnis.common.model.LnisModels.*;

public final class RedisEntities {
    private RedisEntities() {}
    public record AgentEntity(String agentId, AgentRole role, AgentState state, Instant lastSeen, String version,
                              int codecAbiVersion, String os, String architecture, String error) {}
    public record InputBufferEntity(UUID inputId, InputKind kind, String fileName, long declaredSize, long receivedSize,
                                    long chunkCount, long recordCount, String sha256, boolean complete, Instant createdAt, Instant completedAt) {}
    public record TestSessionEntity(UUID sessionId, SessionState state, TestType testType, String senderAgentId,
                                    String receiverAgentId, UUID inputId, int progress, String message, Verdict verdict,
                                    String requestJson, Instant createdAt, Instant updatedAt) {}
}

