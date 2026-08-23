package kr.co.lnis.server.capture.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Sender Agent가 사용할 COM 포트와 GNSS 수집 조건이다. */
public record CaptureRequest(
        @NotBlank(message = "Sender Agent를 선택하세요.") String senderAgentId,
        @NotBlank(message = "수집에 사용할 COM 포트를 선택하세요.") String portName,
        @Min(value = 1200, message = "Baud rate는 1200 이상이어야 합니다.")
        @Max(value = 4_000_000, message = "Baud rate는 4000000 이하여야 합니다.")
        int baudRate,
        @NotBlank(message = "GNSS 수집 프로토콜을 선택하세요.") String protocolId,
        String sessionName,
        String receiverModel,
        String firmwareVersion,
        boolean dtrEnabled,
        boolean rtsEnabled) {}
