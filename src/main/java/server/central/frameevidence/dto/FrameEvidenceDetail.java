package server.central.frameevidence.dto;

/** 브라우저의 100×60 비트 지도에 필요한 네 단계 AFS 프레임 원문과 비교 결과다. byte[]는 JSON에서 Base64 문자열로 직렬화된다. */
@lombok.Value
@lombok.AllArgsConstructor
@lombok.Builder
@lombok.extern.jackson.Jacksonized
@lombok.experimental.Accessors(fluent = true)
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public class FrameEvidenceDetail {
  /** 원문 바이트를 제외한 해시·CRC·차이 개수와 사용자 설명이다. */
  FrameEvidenceSummary summary;

  /** 오류를 넣기 전 Sender 기준 AFSFrame이며 750 byte, 즉 6,000 bit다. */
  byte[] referenceFrame;

  /** 시험 오류를 주입한 뒤 Sender가 UDP에 실어 보낸 750 byte 프레임이다. */
  byte[] transmittedFrame;

  /** UDP 패킷 검사를 통과해 Receiver가 채택한 750 byte 프레임이다. */
  byte[] receivedFrame;

  /** Receiver 복호 결과를 동일한 TOI로 다시 인코딩한 진단용 750 byte 프레임이다. */
  byte[] reencodedFrame;

  /** 기준 프레임과 실제 송신 프레임이 다른 0~5,999 범위의 비트 위치 목록이다. */
  java.util.List<Integer> referenceToTransmittedPositions;

  /** 실제 송신 프레임과 Receiver 수신 프레임이 다른 비트 위치 목록이다. */
  java.util.List<Integer> transmittedToReceivedPositions;

  /** 기준 프레임과 복호 후 재인코딩 프레임이 다른 비트 위치 목록이다. */
  java.util.List<Integer> referenceToReencodedPositions;
}
