package kr.co.lnis.server.frameevidence.dto;

/**
 * 브라우저의 100×60 비트 지도에 필요한 네 단계 AFS 프레임 원문과 비교 결과다.
 * byte[]는 JSON에서 Base64 문자열로 직렬화된다.
 */
public record FrameEvidenceDetail(
        FrameEvidenceSummary summary,
        byte[] referenceFrame,
        byte[] transmittedFrame,
        byte[] receivedFrame,
        byte[] reencodedFrame,
        java.util.List<Integer> referenceToTransmittedPositions,
        java.util.List<Integer> transmittedToReceivedPositions,
        java.util.List<Integer> referenceToReencodedPositions) {
}
