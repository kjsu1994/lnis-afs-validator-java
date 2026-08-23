package kr.co.lnis.server.capture.controller;

import jakarta.validation.Valid;
import kr.co.lnis.common.model.AgentProtocol.CommandType;
import kr.co.lnis.common.model.LnisModels.InputKind;
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
        commands.command(
                request.senderAgentId(),
                input.inputId(),
                CommandType.START_CAPTURE,
                request);
        return input;
    }
    @PostMapping("/{captureId}/stop") public Map<String,Object> stop(@PathVariable UUID captureId, @RequestParam String senderAgentId) {
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
