package kr.co.lnis.server.frameevidence.dto;

import java.util.List;
import kr.co.lnis.protocol.model.LnisModels.Sb2EphemerisResult;

/**
 * 프레임 선택 목록과 CSV에 사용하는 단일 AFS 프레임 비교·복호 진단 요약이다.
 *
 * <p>전체 세션 판정이 아니라 {@link #frameIndex()}에 해당하는 프레임 하나의 결과다.
 */
@lombok.Value
@lombok.Builder
@lombok.extern.jackson.Jacksonized
@lombok.experimental.Accessors(fluent = true)
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public class FrameEvidenceSummary {
  /** 세션 내부의 0부터 시작하는 논리 프레임 번호다. 화면에서는 1을 더해 표시한다. */
  int frameIndex;

  /** 기준·송신 프레임을 포함한 Sender 증거가 H2에 저장됐는지 여부다. */
  boolean senderEvidenceAvailable;

  /** 수신·재인코딩 프레임과 CRC 진단을 포함한 Receiver 증거가 도착했는지 여부다. */
  boolean receiverEvidenceAvailable;

  /** Decoder 완료 후 SB2·SB3·SB4 CRC가 모두 정상인 완전 복호 여부다. */
  boolean decodeSucceeded;

  /** Native Decoder 호출이 예외 없이 결과를 반환했는지 여부이며 CRC 성공과는 다르다. */
  boolean decoderCompleted;

  /** SB2 서브블록의 복호 후 CRC 검사가 정상인지 여부다. */
  boolean sb2CrcValid;

  /** GRAW 조각이 포함될 수 있는 SB3 서브블록의 CRC 정상 여부다. */
  boolean sb3CrcValid;

  /** GRAW 조각이 포함될 수 있는 SB4 서브블록의 CRC 정상 여부다. */
  boolean sb4CrcValid;

  /** SB2 LDPC 처리 중 Decoder 내부 판정이 변경된 횟수이며 주입 오류 개수가 아니다. */
  int sb2DecisionChanges;

  /** SB3 LDPC 처리 중 Decoder 내부 판정이 변경된 횟수다. */
  int sb3DecisionChanges;

  /** SB4 LDPC 처리 중 Decoder 내부 판정이 변경된 횟수다. */
  int sb4DecisionChanges;

  /** SB3와 SB4 CRC가 정상이라 이 프레임의 GRAW 조각을 재조립에 사용했는지 여부다. */
  boolean usedForGrawReassembly;

  /** Receiver가 CRC 정상 SB2에서 해석한 LANS ephemeris와 검증 결과다. */
  Sb2EphemerisResult sb2Ephemeris;

  /** CRC 실패 블록 또는 Decoder 예외를 사용자가 읽을 수 있게 정리한 이유다. */
  String failureReason;

  /** 오류 주입 전 기준 프레임 전체 750 byte의 SHA-256이다. */
  String referenceSha256;

  /** 오류 주입 후 실제 송신 프레임 전체 750 byte의 SHA-256이다. */
  String transmittedSha256;

  /** Receiver가 채택한 수신 프레임 전체 750 byte의 SHA-256이다. */
  String receivedSha256;

  /** 복호 결과를 다시 인코딩한 프레임 전체 750 byte의 SHA-256이다. */
  String reencodedSha256;

  /** 기준과 실제 송신 프레임 사이에서 달라진 비트 수이며 시험 오류 주입 결과다. */
  Integer referenceToTransmittedDifferences;

  /** 실제 송신과 Receiver 수신 프레임 사이에서 달라진 비트 수이며 보통 0이다. */
  Integer transmittedToReceivedDifferences;

  /** 기준과 재인코딩 프레임 사이에 남은 비트 차이 수이며 0이면 6,000비트가 같다. */
  Integer referenceToReencodedDifferences;

  /** Sender가 의도적으로 반전한 0~5,999 범위의 비트 위치 목록이다. */
  List<Integer> injectedBitPositions;

  /** Test D에서 동기 손상 프레임을 설계대로 제외한 경우인지 여부다. */
  boolean intentionalSyncRejection;

  /** 위 진단값을 일반 사용자가 이해할 수 있도록 조합한 프레임별 설명이다. */
  String interpretation;

  public FrameEvidenceSummary(
      /** 세션 내부의 0부터 시작하는 논리 프레임 번호다. 화면에서는 1을 더해 표시한다. */
      int frameIndex,
      /** 기준·송신 프레임을 포함한 Sender 증거가 H2에 저장됐는지 여부다. */
      boolean senderEvidenceAvailable,
      /** 수신·재인코딩 프레임과 CRC 진단을 포함한 Receiver 증거가 도착했는지 여부다. */
      boolean receiverEvidenceAvailable,
      /** Decoder 완료 후 SB2·SB3·SB4 CRC가 모두 정상인 완전 복호 여부다. */
      boolean decodeSucceeded,
      /** Native Decoder 호출이 예외 없이 결과를 반환했는지 여부이며 CRC 성공과는 다르다. */
      boolean decoderCompleted,
      /** SB2 서브블록의 복호 후 CRC 검사가 정상인지 여부다. */
      boolean sb2CrcValid,
      /** GRAW 조각이 포함될 수 있는 SB3 서브블록의 CRC 정상 여부다. */
      boolean sb3CrcValid,
      /** GRAW 조각이 포함될 수 있는 SB4 서브블록의 CRC 정상 여부다. */
      boolean sb4CrcValid,
      /** SB2 LDPC 처리 중 Decoder 내부 판정이 변경된 횟수이며 주입 오류 개수가 아니다. */
      int sb2DecisionChanges,
      /** SB3 LDPC 처리 중 Decoder 내부 판정이 변경된 횟수다. */
      int sb3DecisionChanges,
      /** SB4 LDPC 처리 중 Decoder 내부 판정이 변경된 횟수다. */
      int sb4DecisionChanges,
      /** SB3와 SB4 CRC가 정상이라 이 프레임의 GRAW 조각을 재조립에 사용했는지 여부다. */
      boolean usedForGrawReassembly,
      /** Receiver가 CRC 정상 SB2에서 해석한 LANS ephemeris와 검증 결과다. */
      Sb2EphemerisResult sb2Ephemeris,
      /** CRC 실패 블록 또는 Decoder 예외를 사용자가 읽을 수 있게 정리한 이유다. */
      String failureReason,
      /** 오류 주입 전 기준 프레임 전체 750 byte의 SHA-256이다. */
      String referenceSha256,
      /** 오류 주입 후 실제 송신 프레임 전체 750 byte의 SHA-256이다. */
      String transmittedSha256,
      /** Receiver가 채택한 수신 프레임 전체 750 byte의 SHA-256이다. */
      String receivedSha256,
      /** 복호 결과를 다시 인코딩한 프레임 전체 750 byte의 SHA-256이다. */
      String reencodedSha256,
      /** 기준과 실제 송신 프레임 사이에서 달라진 비트 수이며 시험 오류 주입 결과다. */
      Integer referenceToTransmittedDifferences,
      /** 실제 송신과 Receiver 수신 프레임 사이에서 달라진 비트 수이며 보통 0이다. */
      Integer transmittedToReceivedDifferences,
      /** 기준과 재인코딩 프레임 사이에 남은 비트 차이 수이며 0이면 6,000비트가 같다. */
      Integer referenceToReencodedDifferences,
      /** Sender가 의도적으로 반전한 0~5,999 범위의 비트 위치 목록이다. */
      List<Integer> injectedBitPositions,
      /** Test D에서 동기 손상 프레임을 설계대로 제외한 경우인지 여부다. */
      boolean intentionalSyncRejection,
      /** 위 진단값을 일반 사용자가 이해할 수 있도록 조합한 프레임별 설명이다. */
      String interpretation) {
    injectedBitPositions =
        injectedBitPositions == null ? List.of() : List.copyOf(injectedBitPositions);

    this.frameIndex = frameIndex;
    this.senderEvidenceAvailable = senderEvidenceAvailable;
    this.receiverEvidenceAvailable = receiverEvidenceAvailable;
    this.decodeSucceeded = decodeSucceeded;
    this.decoderCompleted = decoderCompleted;
    this.sb2CrcValid = sb2CrcValid;
    this.sb3CrcValid = sb3CrcValid;
    this.sb4CrcValid = sb4CrcValid;
    this.sb2DecisionChanges = sb2DecisionChanges;
    this.sb3DecisionChanges = sb3DecisionChanges;
    this.sb4DecisionChanges = sb4DecisionChanges;
    this.usedForGrawReassembly = usedForGrawReassembly;
    this.sb2Ephemeris = sb2Ephemeris;
    this.failureReason = failureReason;
    this.referenceSha256 = referenceSha256;
    this.transmittedSha256 = transmittedSha256;
    this.receivedSha256 = receivedSha256;
    this.reencodedSha256 = reencodedSha256;
    this.referenceToTransmittedDifferences = referenceToTransmittedDifferences;
    this.transmittedToReceivedDifferences = transmittedToReceivedDifferences;
    this.referenceToReencodedDifferences = referenceToReencodedDifferences;
    this.injectedBitPositions = injectedBitPositions;
    this.intentionalSyncRejection = intentionalSyncRejection;
    this.interpretation = interpretation;
  }
}
