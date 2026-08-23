package kr.co.lnis.server.capture.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Sender Agent가 사용할 COM 포트와 GNSS 수집 조건이다. */
public record CaptureRequest(
        @NotBlank String senderAgentId,
        @NotBlank String portName,
        @Min(1200) @Max(4_000_000) int baudRate,
        @NotBlank String protocolId,
        String sessionName,
        String receiverModel,
        String firmwareVersion,
        boolean dtrEnabled,
        boolean rtsEnabled) {}
