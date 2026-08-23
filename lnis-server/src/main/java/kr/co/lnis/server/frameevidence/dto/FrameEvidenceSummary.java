package kr.co.lnis.server.frameevidence.dto;

import java.util.List;

/** 프레임 선택 목록과 CSV에 사용하는, 원문 바이트를 제외한 비교 요약이다. */
public record FrameEvidenceSummary(
        int frameIndex,
        boolean senderEvidenceAvailable,
        boolean receiverEvidenceAvailable,
        boolean decodeSucceeded,
        boolean decoderCompleted,
        boolean sb2CrcValid,
        boolean sb3CrcValid,
        boolean sb4CrcValid,
        int sb2DecisionChanges,
        int sb3DecisionChanges,
        int sb4DecisionChanges,
        boolean usedForGrawReassembly,
        String failureReason,
        String referenceSha256,
        String transmittedSha256,
        String receivedSha256,
        String reencodedSha256,
        Integer referenceToTransmittedDifferences,
        Integer transmittedToReceivedDifferences,
        Integer referenceToReencodedDifferences,
        List<Integer> injectedBitPositions,
        boolean intentionalSyncRejection,
        String interpretation) {
    public FrameEvidenceSummary {
        injectedBitPositions = injectedBitPositions == null
                ? List.of()
                : List.copyOf(injectedBitPositions);
    }
}
