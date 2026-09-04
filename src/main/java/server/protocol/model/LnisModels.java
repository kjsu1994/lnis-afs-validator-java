package server.protocol.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 서버, Agent 및 다운로드 산출물이 함께 사용하는 시험 도메인 모델 모음이다.
 *
 * <p>이 타입들은 Spring Entity가 아니라 실행 환경에 독립적인 protocol 모델이다. Lombok {@code @Value}로 불변성을 유지해 비동기
 * WebSocket 처리 중 값이 변경되지 않게 하고, collection은 생성자에서 방어적 복사해 외부 변경을 차단한다.
 */
public final class LnisModels {
  private LnisModels() {}

  /** Agent 프로세스가 수행하는 고정 역할이다. */
  public enum AgentRole {
    /** GRAW를 AFS 프레임으로 만들고 UDP로 전송한다. */
    SENDER,
    /** UDP 프레임을 받아 AFS를 복호화하고 GRAW를 복원한다. */
    RECEIVER
  }

  /** 중앙 서버가 관찰하는 Agent 연결 및 작업 상태다. */
  public enum AgentState {
    /** WebSocket 연결이 없거나 Heartbeat 유효 시간이 지난 상태다. */
    OFFLINE,
    /** Agent가 서버 연결 또는 초기화를 진행 중인 상태다. */
    CONNECTING,
    /** 새 명령을 받을 수 있는 정상 대기 상태다. */
    READY,
    /** GNSS 수집 또는 시험 송수신 명령을 수행 중인 상태다. */
    BUSY,
    /** Agent 처리 오류로 운영자 확인이 필요한 상태다. */
    ERROR
  }

  /** WPF Validator와 대응되는 Test A~E 시험 종류다. */
  public enum TestType {
    /** 오류를 주입하지 않고 정상 송수신과 원본 복원을 검증한다. */
    TEST_A_NORMAL,
    /** 데이터 영역의 비트를 Seed 기반 임의 위치에서 반전한다. */
    TEST_B_RANDOM_ERRORS,
    /** 데이터 영역에서 연속된 비트 구간을 반전해 Burst 오류 복구를 검증한다. */
    TEST_C_BURST_ERRORS,
    /** 동기 영역을 훼손하고 다음 정상 동기 프레임 탐색 여부를 검증한다. */
    TEST_D_SYNC_RECOVERY,
    /** Sender가 일부 UDP 복제본을 의도적으로 보내지 않아 Drop 내성을 검증한다. */
    TEST_E_UDP_DROP
  }

  /** 시험 생성부터 종료까지 중앙 서버가 관리하는 상태값이다. */
  public enum SessionState {
    /** 세션 레코드가 생성됐지만 Agent 명령을 아직 시작하지 않은 상태다. */
    CREATED,
    /** Receiver가 UDP 수신 준비를 완료하기를 기다리는 상태다. */
    WAITING_RECEIVER,
    /** Sender와 Receiver가 시험 데이터를 송수신하는 상태다. */
    TRANSMITTING,
    /** 수신 데이터 복호화, GRAW 재조립과 무결성 판정을 수행하는 상태다. */
    EVALUATING,
    /** 양쪽 결과 수집까지 정상적으로 종료된 상태다. 판정은 PASS 또는 FAIL일 수 있다. */
    COMPLETED,
    /** 사용자 또는 서버 정리 로직이 시험을 취소한 상태다. */
    CANCELLED,
    /** 명령 전달, 타임아웃 또는 내부 오류로 시험 자체가 비정상 종료된 상태다. */
    FAILED,
    /** 결과가 부족해 PASS/FAIL을 확정하지 못한 종료 상태다. */
    INCONCLUSIVE
  }

  /** TX/RX 또는 최종 세션의 판정 결과다. */
  public enum Verdict {
    /** 해당 시험 유형의 성공 조건을 모두 충족했다. */
    PASS,
    /** 시험은 완료됐지만 하나 이상의 필수 판정 조건을 충족하지 못했다. */
    FAIL,
    /** 결과 미수신 등으로 성공과 실패를 확정할 근거가 부족하다. */
    INCONCLUSIVE
  }

  /** 측정 지표가 속한 기능 영역이다. */
  public enum MetricCategory {
    NETWORK,
    ROUTING,
    PVT,
    SYSTEM,
    DATA_INTEGRITY
  }

  /** 지표의 임계치 판정 또는 단순 측정 상태다. */
  public enum MetricStatus {
    PASS,
    FAIL,
    MEASURED,
    NOT_APPLICABLE
  }

  /** 시험 입력이 생성된 경로를 구분한다. */
  public enum InputKind {
    /** 브라우저가 기존 {@code .graw} 파일을 청크 업로드한 입력이다. */
    GRAW_UPLOAD,
    /** Sender Agent가 COM 포트에서 실시간 GNSS 데이터를 수집해 생성한 입력이다. */
    GNSS_CAPTURE
  }

  /** 지표가 통과해야 할 최소값 또는 최대값 조건이다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class MetricThreshold {
    /** 이 임계값을 실제 PASS/FAIL 판정에 사용할지 여부다. */
    boolean enabled;

    /** 지표와 비교할 임계 숫자이며 단위는 해당 {@link Metric#unit()}을 따른다. */
    double value;

    /** {@code true}면 최소 허용값, {@code false}면 최대 허용값으로 비교한다. */
    boolean minimum;
  }

  /**
   * Sender/Receiver UDP 통신 설정이다.
   *
   * <p>숫자 필드가 0이면 기존 WPF 동작과 동일한 기본값을 적용한다. 값의 허용 범위와 두 포트의 중복 여부는 실제 세션을 생성하는 서버 service가 검증한다.
   */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class TransportSettings {
    /** Sender가 FRAME 데이터그램을 보낼 IPv4 주소다. 로컬 시험에서는 {@code 127.0.0.1}이다. */
    String broadcastAddress;

    /** AFS FRAME과 SESSION 제어 패킷을 Receiver가 수신할 UDP 포트다. */
    int dataPort;

    /** Receiver가 최종 압축 결과를 Sender로 돌려보낼 UDP 포트다. */
    int resultPort;

    /** 손실 내성을 위해 동일 논리 패킷을 반복 송신하는 총 횟수다. */
    int repeatCount;

    /** 다음 패킷 또는 Receiver 결과를 기다리는 최대 시간이며 단위는 second다. */
    int resultTimeoutSeconds;

    /** SESSION_END 이후 늦게 도착한 반복 패킷까지 받을 유예 시간이며 단위는 millisecond다. */
    int endGraceMilliseconds;

    /** 네트워크 Probe를 사용하는 경우 시도 간격이며 단위는 millisecond다. */
    int probeIntervalMilliseconds;

    public TransportSettings(
        /** Sender가 FRAME 데이터그램을 보낼 IPv4 주소다. 로컬 시험에서는 {@code 127.0.0.1}이다. */
        String broadcastAddress,
        /** AFS FRAME과 SESSION 제어 패킷을 Receiver가 수신할 UDP 포트다. */
        int dataPort,
        /** Receiver가 최종 압축 결과를 Sender로 돌려보낼 UDP 포트다. */
        int resultPort,
        /** 손실 내성을 위해 동일 논리 패킷을 반복 송신하는 총 횟수다. */
        int repeatCount,
        /** 다음 패킷 또는 Receiver 결과를 기다리는 최대 시간이며 단위는 second다. */
        int resultTimeoutSeconds,
        /** SESSION_END 이후 늦게 도착한 반복 패킷까지 받을 유예 시간이며 단위는 millisecond다. */
        int endGraceMilliseconds,
        /** 네트워크 Probe를 사용하는 경우 시도 간격이며 단위는 millisecond다. */
        int probeIntervalMilliseconds) {
      broadcastAddress = blankToDefault(broadcastAddress, "255.255.255.255");
      if (dataPort == 0) dataPort = 45821;
      if (resultPort == 0) resultPort = 45822;
      if (repeatCount == 0) repeatCount = 3;
      if (resultTimeoutSeconds == 0) resultTimeoutSeconds = 30;
      if (endGraceMilliseconds == 0) endGraceMilliseconds = 1000;
      if (probeIntervalMilliseconds == 0) probeIntervalMilliseconds = 1000;

      this.broadcastAddress = broadcastAddress;
      this.dataPort = dataPort;
      this.resultPort = resultPort;
      this.repeatCount = repeatCount;
      this.resultTimeoutSeconds = resultTimeoutSeconds;
      this.endGraceMilliseconds = endGraceMilliseconds;
      this.probeIntervalMilliseconds = probeIntervalMilliseconds;
    }
  }

  /** AFS 프레임 payload 생성에 적용할 설정이다. */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class AfsSettings {
    Integer prn;

    public AfsSettings(Integer prn) {
      if (prn == null) {
        prn = 1;
      }

      if (prn < 1 || prn > 8) {
        throw new IllegalArgumentException("AFS PRN must be 1 to 8");
      }

      this.prn = prn;
    }
  }

  /** Receiver가 CRC 정상 SB2에서 해석한 LANS ephemeris와 검증 결과다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Sb2EphemerisResult {
    String profileId;
    int prn;
    int week;

    /** GPS 주 내 1,200초 구간 번호인 AFS ITOW다. u-blox iTOW(ms)와 다르다. */
    int afsItow;

    int toeSeconds;
    double eccentricity;
    double sqrtSemiMajorAxis;
    double inclinationRadians;
    double ascendingNodeRadians;
    double argumentOfPerigeeRadians;
    double meanAnomalyRadians;
    int tocSeconds;
    double af0Seconds;
    double af1SecondsPerSecond;
    boolean headerMatchesPacket;
    boolean ephemerisMatchesConfigured;
    boolean tailTestPatternValid;
  }

  /**
   * Test A~E에 공통으로 전달되는 오류 주입 및 판정 옵션이다.
   *
   * <p>seed 값은 시험 재현성에 사용되므로 동일 입력과 seed 조합에서 오류 위치가 바뀌면 안 된다.
   */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class TestOptions {
    /** 실행할 Test A~E 유형이며 {@code null}이면 Test A를 사용한다. */
    TestType testType;

    /** Test B/C/D에서 프레임 하나당 의도적으로 반전할 비트 개수다. */
    int errorCount;

    /** Test B/C 오류 위치를 반복 재현하기 위한 의사 난수 Seed다. */
    int errorSeed;

    /** Test D에서 동기 영역 손상 위치를 계산할 간격 설정이다. */
    int syncDamageInterval;

    /** Test E에서 각 UDP 복제본을 미전송할 설정 확률이며 단위는 percent다. */
    double dropRatePercent;

    /** Test E의 Drop 결정을 반복 재현하기 위한 의사 난수 Seed다. */
    int dropSeed;

    /** 지표 이름을 키로 사용하는 선택적 사용자 판정 임계값이다. */
    Map<String, MetricThreshold> thresholds;

    public TestOptions(
        /** 실행할 Test A~E 유형이며 {@code null}이면 Test A를 사용한다. */
        TestType testType,
        /** Test B/C/D에서 프레임 하나당 의도적으로 반전할 비트 개수다. */
        int errorCount,
        /** Test B/C 오류 위치를 반복 재현하기 위한 의사 난수 Seed다. */
        int errorSeed,
        /** Test D에서 동기 영역 손상 위치를 계산할 간격 설정이다. */
        int syncDamageInterval,
        /** Test E에서 각 UDP 복제본을 미전송할 설정 확률이며 단위는 percent다. */
        double dropRatePercent,
        /** Test E의 Drop 결정을 반복 재현하기 위한 의사 난수 Seed다. */
        int dropSeed,
        /** 지표 이름을 키로 사용하는 선택적 사용자 판정 임계값이다. */
        Map<String, MetricThreshold> thresholds) {
      testType = testType == null ? TestType.TEST_A_NORMAL : testType;
      if (errorCount == 0) errorCount = 1;
      if (errorSeed == 0) errorSeed = 1;
      if (syncDamageInterval == 0) syncDamageInterval = 10;
      if (dropSeed == 0) dropSeed = 1;
      thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);

      this.testType = testType;
      this.errorCount = errorCount;
      this.errorSeed = errorSeed;
      this.syncDamageInterval = syncDamageInterval;
      this.dropRatePercent = dropRatePercent;
      this.dropSeed = dropSeed;
      this.thresholds = thresholds;
    }
  }

  /** 완료 검증된 입력을 Agent에 전달할 때 사용하는 크기·해시 manifest다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class InputManifest {
    /** Redis 입력 버퍼의 UUID다. */
    UUID inputId;

    /** 파일 업로드 또는 GNSS 실시간 수집 구분값이다. */
    InputKind kind;

    /** 화면과 결과 파일에 표시할 원본 파일 이름이다. */
    String fileName;

    /** 완료된 입력 전체 크기이며 단위는 byte다. */
    long size;

    /** 입력 전체 바이트의 대문자 16진수 SHA-256이다. */
    String sha256;

    /** 구조와 CRC 검사를 통과한 GRAW 레코드 개수다. */
    long recordCount;

    /** Redis에 분할 저장된 입력 청크 개수다. */
    long chunkCount;

    /** 서버가 입력 완료 검증을 확정한 UTC 시각이다. */
    Instant completedAt;
  }

  /** 원본과 Receiver 복원 결과의 길이, record 수 및 SHA-256 비교 결과다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class IntegrityResult {
    /** 길이·레코드 수·SHA-256 등 해당 시험의 무결성 조건을 충족했는지 여부다. */
    boolean success;

    /** Sender가 사용한 원본 GRAW 전체 크기이며 단위는 byte다. */
    long sourceLength;

    /** Receiver가 정상 프레임에서 재조립한 GRAW 크기이며 단위는 byte다. */
    long reconstructedLength;

    /** 원본 GRAW 전체 바이트의 SHA-256이다. */
    String sourceSha256;

    /** Receiver 재조립 GRAW 전체 바이트의 SHA-256이다. */
    String reconstructedSha256;

    /** 원본 GRAW에 포함된 정상 레코드 개수다. */
    long expectedRecords;

    /** Receiver가 모든 조각을 모아 완성한 GRAW 레코드 개수다. */
    long reconstructedRecords;

    /** 성공 또는 불일치 원인을 로그와 JSON에 제공하는 설명이다. */
    String detail;
  }

  /** 결과 화면과 summary CSV에 표시되는 단일 측정 지표다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Metric {
    /** 지표가 속한 네트워크·시스템·무결성 등의 영역이다. */
    MetricCategory category;

    /** 코드와 CSV에서 사용하는 안정적인 지표 식별 이름이다. */
    String name;

    /** 사용자가 지표의 의미를 이해할 수 있도록 제공하는 설명이다. */
    String description;

    /** {@code frame}, {@code bit}, {@code byte}, {@code ms} 등의 표시 단위다. */
    String unit;

    /** 측정 숫자이며 적용할 수 없는 지표에서는 {@code null}일 수 있다. */
    Double value;

    /** 임계값 판정 결과 또는 단순 측정값임을 나타내는 상태다. */
    MetricStatus status;

    /** 이 지표에 적용된 선택적 최소/최대 임계값이다. */
    MetricThreshold threshold;

    /** 판정 근거나 계산 방법을 보완하는 선택적 상세 설명이다. */
    String detail;
  }

  /**
   * 논리 frame, 실제 datagram, 중복/손상/Drop 및 전송 시간 카운터다.
   *
   * <p>{@code corruptDatagrams}는 구버전 JSON 호환을 위해 유지하는 합계 필드다. 새 화면과 산출물에서는 UDP 계층의 {@code
   * invalidDatagrams}, AFS 계층의 {@code decodeFailedFrames}, 시험에서 의도한 {@code injectedBitCount}와
   * {@code syncRejectedFrames}를 각각 사용해야 단위가 섞이지 않는다.
   */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class NetworkCounters {
    /** Sender가 원본 GRAW로부터 생성한 중복 제거 기준 논리 AFS 프레임 수다. */
    long expectedLogicalFrames;

    /** Receiver가 동일 sequence 중복을 제거하고 채택한 논리 AFS 프레임 수다. */
    long receivedLogicalFrames;

    /** Sender가 준비한 FRAME 데이터그램 송신 횟수이며 반복 송신분을 포함한다. */
    long sentDatagrams;

    /** Receiver가 받은 FRAME·SESSION_START 등 모든 시험 UDP 데이터그램 수다. */
    long receivedDatagrams;

    /** 같은 kind와 sequence가 이미 처리되어 제외된 반복 데이터그램 수다. */
    long duplicateDatagrams;

    /** 호환성을 위해 유지하는 UDP 해석 실패 수이며 신규 화면에서는 {@code invalidDatagrams}를 사용한다. */
    long corruptDatagrams;

    /** 네트워크 Probe 요청을 송신한 횟수다. */
    long probeAttempts;

    /** 상대 Agent에서 정상 응답한 Probe 횟수다. */
    long probeResponses;

    /** 이 역할이 처리하거나 복원한 원시 데이터 크기이며 단위는 byte다. */
    long rawBytes;

    /** 역할별 송수신 작업 시작부터 결과 생성까지 걸린 시간이다. */
    Duration transferDuration;

    /** Probe로 계산한 개별 단방향 지연 추정값 목록이며 단위는 millisecond다. */
    List<Double> oneWayLatencyMilliseconds;

    /** Test E 설정에 따라 Sender가 실제로 보내지 않은 UDP 복제본 수다. */
    long simulatedDroppedDatagrams;

    /** Test E에 설정한 의도적 미전송 확률이며 단위는 percent다. */
    double configuredDropRatePercent;

    /** LNIS 패킷 구조 또는 패킷 CRC를 해석하지 못해 폐기한 UDP 데이터그램 수다. */
    long invalidDatagrams;

    /** Decoder 예외 또는 SB2·SB3·SB4 중 하나 이상의 CRC 실패가 발생한 AFS 프레임 수다. */
    long decodeFailedFrames;

    /** Test B/C/D가 AFS 프레임 내부에 의도적으로 반전한 비트의 전체 합계다. */
    long injectedBitCount;

    /** Test D에서 동기 패턴 손상으로 정상 프레임 후보에서 제외한 프레임 수다. */
    long syncRejectedFrames;

    public NetworkCounters(
        /** Sender가 원본 GRAW로부터 생성한 중복 제거 기준 논리 AFS 프레임 수다. */
        long expectedLogicalFrames,
        /** Receiver가 동일 sequence 중복을 제거하고 채택한 논리 AFS 프레임 수다. */
        long receivedLogicalFrames,
        /** Sender가 준비한 FRAME 데이터그램 송신 횟수이며 반복 송신분을 포함한다. */
        long sentDatagrams,
        /** Receiver가 받은 FRAME·SESSION_START 등 모든 시험 UDP 데이터그램 수다. */
        long receivedDatagrams,
        /** 같은 kind와 sequence가 이미 처리되어 제외된 반복 데이터그램 수다. */
        long duplicateDatagrams,
        /** 호환성을 위해 유지하는 UDP 해석 실패 수이며 신규 화면에서는 {@code invalidDatagrams}를 사용한다. */
        long corruptDatagrams,
        /** 네트워크 Probe 요청을 송신한 횟수다. */
        long probeAttempts,
        /** 상대 Agent에서 정상 응답한 Probe 횟수다. */
        long probeResponses,
        /** 이 역할이 처리하거나 복원한 원시 데이터 크기이며 단위는 byte다. */
        long rawBytes,
        /** 역할별 송수신 작업 시작부터 결과 생성까지 걸린 시간이다. */
        Duration transferDuration,
        /** Probe로 계산한 개별 단방향 지연 추정값 목록이며 단위는 millisecond다. */
        List<Double> oneWayLatencyMilliseconds,
        /** Test E 설정에 따라 Sender가 실제로 보내지 않은 UDP 복제본 수다. */
        long simulatedDroppedDatagrams,
        /** Test E에 설정한 의도적 미전송 확률이며 단위는 percent다. */
        double configuredDropRatePercent,
        /** LNIS 패킷 구조 또는 패킷 CRC를 해석하지 못해 폐기한 UDP 데이터그램 수다. */
        long invalidDatagrams,
        /** Decoder 예외 또는 SB2·SB3·SB4 중 하나 이상의 CRC 실패가 발생한 AFS 프레임 수다. */
        long decodeFailedFrames,
        /** Test B/C/D가 AFS 프레임 내부에 의도적으로 반전한 비트의 전체 합계다. */
        long injectedBitCount,
        /** Test D에서 동기 패턴 손상으로 정상 프레임 후보에서 제외한 프레임 수다. */
        long syncRejectedFrames) {
      transferDuration = transferDuration == null ? Duration.ZERO : transferDuration;
      oneWayLatencyMilliseconds =
          oneWayLatencyMilliseconds == null ? List.of() : List.copyOf(oneWayLatencyMilliseconds);

      this.expectedLogicalFrames = expectedLogicalFrames;
      this.receivedLogicalFrames = receivedLogicalFrames;
      this.sentDatagrams = sentDatagrams;
      this.receivedDatagrams = receivedDatagrams;
      this.duplicateDatagrams = duplicateDatagrams;
      this.corruptDatagrams = corruptDatagrams;
      this.probeAttempts = probeAttempts;
      this.probeResponses = probeResponses;
      this.rawBytes = rawBytes;
      this.transferDuration = transferDuration;
      this.oneWayLatencyMilliseconds = oneWayLatencyMilliseconds;
      this.simulatedDroppedDatagrams = simulatedDroppedDatagrams;
      this.configuredDropRatePercent = configuredDropRatePercent;
      this.invalidDatagrams = invalidDatagrams;
      this.decodeFailedFrames = decodeFailedFrames;
      this.injectedBitCount = injectedBitCount;
      this.syncRejectedFrames = syncRejectedFrames;
    }
  }

  /** Agent 프로세스의 특정 시점 CPU 및 Working Set 측정값이다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class ResourceSample {
    /** 자원 값을 측정한 UTC 시각이다. */
    Instant timestamp;

    /** Agent JVM 프로세스의 CPU 사용률이며 단위는 percent다. */
    double cpuPercent;

    /** 운영체제가 Agent 프로세스에 할당한 Working Set 크기이며 단위는 byte다. */
    long workingSetBytes;
  }

  /** Sender 또는 Receiver 한 역할이 생성한 최종 시험 결과다. */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class RoleResult {
    /** JSON/CSV 결과 계약의 스키마 버전이다. */
    int schemaVersion;

    /** 이 결과가 속한 시험 세션 UUID다. */
    UUID sessionId;

    /** 결과를 생성한 Sender 또는 Receiver 역할이다. */
    AgentRole role;

    /** 해당 역할 관점에서 계산한 최종 판정이다. */
    Verdict verdict;

    /** Agent가 결과 객체 생성을 완료한 UTC 시각이다. */
    Instant completedAt;

    /** 원본과 Receiver 복원 GRAW의 크기·레코드·SHA-256 비교 결과다. */
    IntegrityResult integrity;

    /** CRC 정상 프레임 수와 Decoder 처리량 등의 측정 지표 목록이다. */
    List<Metric> metrics;

    /** 논리 프레임, UDP, Drop 및 오류 주입 누적 카운터다. */
    NetworkCounters counters;

    /** 시험 중 수집한 Agent 프로세스 자원 사용량 표본 목록이다. */
    List<ResourceSample> samples;

    /** FAIL 또는 내부 오류의 대표 설명이며 정상 결과에서는 {@code null}이다. */
    String error;

    public RoleResult(
        /** JSON/CSV 결과 계약의 스키마 버전이다. */
        int schemaVersion,
        /** 이 결과가 속한 시험 세션 UUID다. */
        UUID sessionId,
        /** 결과를 생성한 Sender 또는 Receiver 역할이다. */
        AgentRole role,
        /** 해당 역할 관점에서 계산한 최종 판정이다. */
        Verdict verdict,
        /** Agent가 결과 객체 생성을 완료한 UTC 시각이다. */
        Instant completedAt,
        /** 원본과 Receiver 복원 GRAW의 크기·레코드·SHA-256 비교 결과다. */
        IntegrityResult integrity,
        /** CRC 정상 프레임 수와 Decoder 처리량 등의 측정 지표 목록이다. */
        List<Metric> metrics,
        /** 논리 프레임, UDP, Drop 및 오류 주입 누적 카운터다. */
        NetworkCounters counters,
        /** 시험 중 수집한 Agent 프로세스 자원 사용량 표본 목록이다. */
        List<ResourceSample> samples,
        /** FAIL 또는 내부 오류의 대표 설명이며 정상 결과에서는 {@code null}이다. */
        String error) {
      metrics = metrics == null ? List.of() : List.copyOf(metrics);
      samples = samples == null ? List.of() : List.copyOf(samples);

      this.schemaVersion = schemaVersion;
      this.sessionId = sessionId;
      this.role = role;
      this.verdict = verdict;
      this.completedAt = completedAt;
      this.integrity = integrity;
      this.metrics = metrics;
      this.counters = counters;
      this.samples = samples;
      this.error = error;
    }
  }

  /** REST 조회와 브라우저 복구에 사용하는 현재 세션 상태 및 양쪽 결과 snapshot이다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class SessionSnapshot {
    /** REST 조회 대상 시험 세션 UUID다. */
    UUID sessionId;

    /** 서버가 관리하는 현재 세션 실행 상태다. */
    SessionState state;

    /** 세션 생성 시 선택한 Test A~E 유형이다. */
    TestType testType;

    /** 프레임 생성·송신을 담당한 Agent ID다. */
    String senderAgentId;

    /** 프레임 수신·복호화를 담당한 Agent ID다. */
    String receiverAgentId;

    /** 시험에 사용한 완료 입력 버퍼 UUID다. */
    UUID inputId;

    /** 브라우저에 표시하는 0~100 범위 전체 진행률이다. */
    int progress;

    /** 현재 단계 또는 종료 상태를 설명하는 사용자용 메시지다. */
    String message;

    /** TX/RX 결과를 종합한 세션 최종 판정이다. */
    Verdict verdict;

    /** 세션을 생성한 UTC 시각이다. */
    Instant createdAt;

    /** 세션 상태가 마지막으로 갱신된 UTC 시각이다. */
    Instant updatedAt;

    /** Sender 결과이며 아직 도착하지 않았다면 {@code null}이다. */
    RoleResult txResult;

    /** Receiver 결과이며 아직 도착하지 않았다면 {@code null}이다. */
    RoleResult rxResult;
  }

  /** null 또는 공백 문자열만 fallback으로 치환하고 유효한 입력은 그대로 유지한다. */
  public static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
