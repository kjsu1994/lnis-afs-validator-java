package kr.co.lnis.protocol.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 서버, Agent 및 다운로드 산출물이 함께 사용하는 시험 도메인 모델 모음이다.
 *
 * <p>이 타입들은 Spring Entity가 아니라 실행 환경에 독립적인 protocol 모델이다. record의 불변성을
 * 이용해 비동기 WebSocket 처리 중 값이 변경되지 않게 하고, collection은 compact constructor에서
 * 방어적 복사해 외부 변경을 차단한다.
 */
public final class LnisModels {
    private LnisModels() {}

    /** Agent 프로세스가 수행하는 고정 역할이다. */
    public enum AgentRole { SENDER, RECEIVER }
    /** 중앙 서버가 관찰하는 Agent 연결 및 작업 상태다. */
    public enum AgentState { OFFLINE, CONNECTING, READY, BUSY, ERROR }
    /** WPF Validator와 대응되는 Test A~E 시험 종류다. */
    public enum TestType { TEST_A_NORMAL, TEST_B_RANDOM_ERRORS, TEST_C_BURST_ERRORS, TEST_D_SYNC_RECOVERY, TEST_E_UDP_DROP }
    /** 시험 생성부터 종료까지 중앙 서버가 관리하는 상태값이다. */
    public enum SessionState { CREATED, WAITING_RECEIVER, TRANSMITTING, EVALUATING, COMPLETED, CANCELLED, FAILED, INCONCLUSIVE }
    /** TX/RX 또는 최종 세션의 판정 결과다. */
    public enum Verdict { PASS, FAIL, INCONCLUSIVE }
    /** 지표가 설명하는 시스템 영역이다. */
    public enum MetricCategory { NETWORK, ROUTING, PVT, SYSTEM, DATA_INTEGRITY }
    /** 지표의 임계치 판정 또는 단순 측정 상태다. */
    public enum MetricStatus { PASS, FAIL, MEASURED, NOT_APPLICABLE }
    /** 시험 입력이 파일 업로드인지 실시간 GNSS 수집인지 구분한다. */
    public enum InputKind { GRAW_UPLOAD, GNSS_CAPTURE }

    /** 지표가 통과해야 할 최소값 또는 최대값 조건이다. */
    public record MetricThreshold(boolean enabled, double value, boolean minimum) {}

    /**
     * Sender/Receiver UDP 통신 설정이다.
     *
     * <p>숫자 필드가 0이면 기존 WPF 동작과 동일한 기본값을 적용한다. 값의 허용 범위와 두 포트의
     * 중복 여부는 실제 세션을 생성하는 서버 service가 검증한다.
     */
    public record TransportSettings(
            String broadcastAddress,
            int dataPort,
            int resultPort,
            int repeatCount,
            int resultTimeoutSeconds,
            int endGraceMilliseconds,
            int probeIntervalMilliseconds) {
        public TransportSettings {
            broadcastAddress = blankToDefault(broadcastAddress, "255.255.255.255");
            if (dataPort == 0) dataPort = 45821;
            if (resultPort == 0) resultPort = 45822;
            if (repeatCount == 0) repeatCount = 3;
            if (resultTimeoutSeconds == 0) resultTimeoutSeconds = 30;
            if (endGraceMilliseconds == 0) endGraceMilliseconds = 1000;
            if (probeIntervalMilliseconds == 0) probeIntervalMilliseconds = 1000;
        }
    }

    /**
     * Test A~E에 공통으로 전달되는 오류 주입 및 판정 옵션이다.
     *
     * <p>seed 값은 시험 재현성에 사용되므로 동일 입력과 seed 조합에서 오류 위치가 바뀌면 안 된다.
     */
    public record TestOptions(
            TestType testType,
            int errorCount,
            int errorSeed,
            int syncDamageInterval,
            double dropRatePercent,
            int dropSeed,
            Map<String, MetricThreshold> thresholds) {
        public TestOptions {
            testType = testType == null ? TestType.TEST_A_NORMAL : testType;
            if (errorCount == 0) errorCount = 1;
            if (errorSeed == 0) errorSeed = 1;
            if (syncDamageInterval == 0) syncDamageInterval = 10;
            if (dropSeed == 0) dropSeed = 1;
            thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        }
    }

    /** 완료된 입력을 Agent에 전달할 때 사용하는 크기와 hash manifest다. */
    public record InputManifest(
            UUID inputId,
            InputKind kind,
            String fileName,
            long size,
            String sha256,
            long recordCount,
            long chunkCount,
            Instant completedAt) {}

    /** 원본과 Receiver 복원 결과의 길이, record 수 및 SHA-256 비교 결과다. */
    public record IntegrityResult(
            boolean success,
            long sourceLength,
            long reconstructedLength,
            String sourceSha256,
            String reconstructedSha256,
            long expectedRecords,
            long reconstructedRecords,
            String detail) {}

    /** 결과 화면과 summary CSV에 표시되는 단일 측정 지표다. */
    public record Metric(
            MetricCategory category,
            String name,
            String description,
            String unit,
            Double value,
            MetricStatus status,
            MetricThreshold threshold,
            String detail) {}

    /**
     * 논리 frame, 실제 datagram, 중복/손상/Drop 및 전송 시간 카운터다.
     *
     * <p>{@code corruptDatagrams}는 구버전 JSON 호환을 위해 유지하는 합계 필드다. 새 화면과
     * 산출물에서는 UDP 계층의 {@code invalidDatagrams}, AFS 계층의
     * {@code decodeFailedFrames}, 시험에서 의도한 {@code injectedBitCount}와
     * {@code syncRejectedFrames}를 각각 사용해야 단위가 섞이지 않는다.
     */
    public record NetworkCounters(
            long expectedLogicalFrames,
            long receivedLogicalFrames,
            long sentDatagrams,
            long receivedDatagrams,
            long duplicateDatagrams,
            long corruptDatagrams,
            long probeAttempts,
            long probeResponses,
            long rawBytes,
            Duration transferDuration,
            List<Double> oneWayLatencyMilliseconds,
            long simulatedDroppedDatagrams,
            double configuredDropRatePercent,
            long invalidDatagrams,
            long decodeFailedFrames,
            long injectedBitCount,
            long syncRejectedFrames) {
        public NetworkCounters {
            transferDuration = transferDuration == null ? Duration.ZERO : transferDuration;
            oneWayLatencyMilliseconds = oneWayLatencyMilliseconds == null ? List.of() : List.copyOf(oneWayLatencyMilliseconds);
        }
    }

    /** Agent 프로세스의 특정 시점 CPU 및 working set 측정값이다. */
    public record ResourceSample(Instant timestamp, double cpuPercent, long workingSetBytes) {}

    /** Sender 또는 Receiver 한 역할이 생성한 최종 시험 결과다. */
    public record RoleResult(
            int schemaVersion,
            UUID sessionId,
            AgentRole role,
            Verdict verdict,
            Instant completedAt,
            IntegrityResult integrity,
            List<Metric> metrics,
            NetworkCounters counters,
            List<ResourceSample> samples,
            String error) {
        public RoleResult {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    /** REST 조회와 브라우저 복구에 사용하는 현재 세션 상태 및 양쪽 결과 snapshot이다. */
    public record SessionSnapshot(
            UUID sessionId,
            SessionState state,
            TestType testType,
            String senderAgentId,
            String receiverAgentId,
            UUID inputId,
            int progress,
            String message,
            Verdict verdict,
            Instant createdAt,
            Instant updatedAt,
            RoleResult txResult,
            RoleResult rxResult) {}

    /** null 또는 공백 문자열만 fallback으로 치환하고 유효한 입력은 그대로 유지한다. */
    public static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
