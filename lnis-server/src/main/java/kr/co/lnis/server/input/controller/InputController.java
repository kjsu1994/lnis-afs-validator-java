package kr.co.lnis.server.input.controller;

import jakarta.validation.Valid;
import kr.co.lnis.server.input.dto.CreateInputRequest;
import kr.co.lnis.server.input.entity.InputBufferEntity;
import kr.co.lnis.server.input.service.InputBufferService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/inputs")
/** GRAW 입력을 청크 단위로 등록하고 검증하는 API를 제공한다. */
public class InputController {
    private final InputBufferService inputs;
    public InputController(InputBufferService inputs) { this.inputs = inputs; }
    @PostMapping
    public InputBufferEntity create(@Valid @RequestBody CreateInputRequest request) {
        return inputs.create(request.fileName(), request.size(), request.kind());
    }

    @PutMapping(value = "/{inputId}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public InputBufferEntity chunk(
            @PathVariable UUID inputId,
            @PathVariable long index,
            @RequestBody byte[] body) {
        return inputs.append(inputId, index, body);
    }

    @PostMapping("/{inputId}/complete")
    public InputBufferEntity complete(@PathVariable UUID inputId) {
        return inputs.complete(inputId);
    }

    @GetMapping("/{inputId}")
    public InputBufferEntity get(@PathVariable UUID inputId) {
        return inputs.get(inputId);
    }

    @DeleteMapping("/{inputId}")
    public Map<String, Boolean> delete(@PathVariable UUID inputId) {
        inputs.remove(inputId);
        return Map.of("removed", true);
    }
}
