package kr.co.lnis.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import kr.co.lnis.common.model.LnisModels.*;

public final class ApiDtos {
    private ApiDtos() {}
    public record CreateInputRequest(@NotBlank String fileName, @PositiveOrZero long size, InputKind kind) {}
    public record InputResponse(UUID inputId, String state, long receivedSize, long recordCount, String sha256) {}
    public record CaptureRequest(@NotBlank String senderAgentId, @NotBlank String portName,
                                 @Min(1200) @Max(4_000_000) int baudRate, @NotBlank String protocolId,
                                 String sessionName, String receiverModel, String firmwareVersion,
                                 boolean dtrEnabled, boolean rtsEnabled) {}
    public record CreateSessionRequest(@NotBlank String senderAgentId, @NotBlank String receiverAgentId,
                                       @NotNull UUID inputId, @Valid @NotNull TransportSettings transport,
                                       @Valid @NotNull TestOptions options) {}
    public record ApiError(String code, String message, String traceId) {}
    public record AgentCommandResponse(UUID commandId, boolean accepted, String message) {}
    public record CleanupResponse(boolean removed) {}
    public record ThresholdRequest(boolean enabled, double value, boolean minimum) {}
}

