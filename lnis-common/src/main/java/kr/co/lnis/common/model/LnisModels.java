package kr.co.lnis.common.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LnisModels {
    private LnisModels() {}

    public enum AgentRole { SENDER, RECEIVER }
    public enum AgentState { OFFLINE, CONNECTING, READY, BUSY, ERROR }
    public enum TestType { TEST_A_NORMAL, TEST_B_RANDOM_ERRORS, TEST_C_BURST_ERRORS, TEST_D_SYNC_RECOVERY, TEST_E_UDP_DROP }
    public enum SessionState { CREATED, WAITING_RECEIVER, TRANSMITTING, EVALUATING, COMPLETED, CANCELLED, FAILED, INCONCLUSIVE }
    public enum Verdict { PASS, FAIL, INCONCLUSIVE }
    public enum MetricCategory { NETWORK, ROUTING, PVT, SYSTEM, DATA_INTEGRITY }
    public enum MetricStatus { PASS, FAIL, MEASURED, NOT_APPLICABLE }
    public enum InputKind { GRAW_UPLOAD, GNSS_CAPTURE }

    public record MetricThreshold(boolean enabled, double value, boolean minimum) {}

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

    public record InputManifest(
            UUID inputId,
            InputKind kind,
            String fileName,
            long size,
            String sha256,
            long recordCount,
            long chunkCount,
            Instant completedAt) {}

    public record IntegrityResult(
            boolean success,
            long sourceLength,
            long reconstructedLength,
            String sourceSha256,
            String reconstructedSha256,
            long expectedRecords,
            long reconstructedRecords,
            String detail) {}

    public record Metric(
            MetricCategory category,
            String name,
            String description,
            String unit,
            Double value,
            MetricStatus status,
            MetricThreshold threshold,
            String detail) {}

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
            double configuredDropRatePercent) {
        public NetworkCounters {
            transferDuration = transferDuration == null ? Duration.ZERO : transferDuration;
            oneWayLatencyMilliseconds = oneWayLatencyMilliseconds == null ? List.of() : List.copyOf(oneWayLatencyMilliseconds);
        }
    }

    public record ResourceSample(Instant timestamp, double cpuPercent, long workingSetBytes) {}

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

    public static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

