package kr.co.lnis.server.input.entity;

import java.time.Instant;
import java.util.UUID;
import kr.co.lnis.common.model.LnisModels.InputKind;

/** Redis 입력 메타데이터와 청크 수신 진행 상태를 표현한다. */
public record InputBufferEntity(
        UUID inputId,
        InputKind kind,
        String fileName,
        long declaredSize,
        long receivedSize,
        long chunkCount,
        long recordCount,
        String sha256,
        boolean complete,
        Instant createdAt,
        Instant completedAt) {}
