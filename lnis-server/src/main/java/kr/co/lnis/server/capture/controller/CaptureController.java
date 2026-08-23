package kr.co.lnis.server.capture.controller;

import jakarta.validation.Valid;
import kr.co.lnis.protocol.model.AgentProtocol.CommandType;
import kr.co.lnis.protocol.model.LnisModels.InputKind;
import kr.co.lnis.server.agent.service.AgentCommandService;
import kr.co.lnis.server.capture.dto.CaptureRequest;
import kr.co.lnis.server.input.entity.InputBufferEntity;
import kr.co.lnis.server.input.service.InputBufferService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/captures")
/** Sender Agent의 GNSS 수집 시작·종료와 입력 확정을 담당한다. */
public class CaptureController {
    private final InputBufferService inputs;
    private final AgentCommandService commands;

    public CaptureController(InputBufferService inputs, AgentCommandService commands) {
        this.inputs = inputs;
        this.commands = commands;
    }

    @PostMapping
    public InputBufferEntity start(@Valid @RequestBody CaptureRequest request) {
        var input = inputs.create("capture.graw", 0, InputKind.GNSS_CAPTURE);
        try {
            commands.command(
                    request.senderAgentId(),
                    input.inputId(),
                    CommandType.START_CAPTURE,
                    request);
            return input;
        } catch (RuntimeException error) {
            // Agent 조회 또는 명령 전송이 실패하면 시험에 사용할 수 없는 Redis 입력을 남기지 않는다.
            try {
                inputs.remove(input.inputId());
            } catch (RuntimeException cleanupError) {
                // 정리 오류가 원래의 명령 실패 원인을 가리지 않도록 suppressed 예외로 보존한다.
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    @PostMapping("/{captureId}/stop")
    public Map<String, Object> stop(
            @PathVariable UUID captureId,
            @RequestParam String senderAgentId) {
        UUID command = commands.command(
                senderAgentId,
                captureId,
                CommandType.STOP_CAPTURE,
                null);
        return Map.of("commandId", command, "accepted", true);
    }

    @PostMapping("/{captureId}/complete")
    public InputBufferEntity complete(@PathVariable UUID captureId) {
        return inputs.complete(captureId);
    }
}
