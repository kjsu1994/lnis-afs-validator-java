package kr.co.lnis.server.frameevidence.controller;

import kr.co.lnis.server.frameevidence.dto.*;
import kr.co.lnis.server.frameevidence.service.FrameEvidenceService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** AFS 6,000비트 프레임 비교 조회와 JSON/CSV 다운로드 API를 제공한다. */
@RestController
@RequestMapping("/lnis/api/v1/sessions/{sessionId}/frame-evidence")
public class FrameEvidenceController {
    private final FrameEvidenceService evidence;

    public FrameEvidenceController(FrameEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    public List<FrameEvidenceSummary> list(@PathVariable UUID sessionId) {
        return evidence.summaries(sessionId);
    }

    @GetMapping("/{frameIndex}")
    public FrameEvidenceDetail detail(
            @PathVariable UUID sessionId,
            @PathVariable int frameIndex) {
        return evidence.detail(sessionId, frameIndex);
    }

    @GetMapping("/artifacts/{fileName}")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID sessionId,
            @PathVariable String fileName) {
        byte[] body = switch (fileName) {
            case "frame-evidence.json" -> evidence.jsonArtifact(sessionId);
            case "frame-diff-summary.csv" -> evidence.csvArtifact(sessionId);
            default -> throw new IllegalArgumentException("지원하지 않는 프레임 산출물입니다: " + fileName);
        };
        MediaType type = fileName.endsWith(".json")
                ? MediaType.APPLICATION_JSON
                : MediaType.parseMediaType("text/csv;charset=UTF-8");
        return ResponseEntity.ok()
                .contentType(type)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(sessionId + "-" + fileName)
                                .build()
                                .toString())
                .body(body);
    }
}
