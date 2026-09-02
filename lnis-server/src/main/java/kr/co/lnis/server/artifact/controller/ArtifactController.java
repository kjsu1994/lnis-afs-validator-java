package kr.co.lnis.server.artifact.controller;

import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.artifact.service.ArtifactService;
import kr.co.lnis.server.artifact.service.CombinedArtifactService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/sessions")
/** H2에 저장된 역할별 결과를 실제 다운로드 파일로 변환한다. */
public class ArtifactController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ArtifactService artifacts;
    private final CombinedArtifactService combinedArtifacts;

    public ArtifactController(
            ArtifactService artifacts,
            CombinedArtifactService combinedArtifacts) {
        this.artifacts = artifacts;
        this.combinedArtifacts = combinedArtifacts;
    }

    @GetMapping("/{sessionId}/artifacts/{fileName}")
    public ResponseEntity<byte[]> downloadCombined(
            @PathVariable UUID sessionId,
            @PathVariable String fileName) {
        MediaType mediaType = switch (fileName) {
            case "lnis-report.json" -> MediaType.APPLICATION_JSON;
            case "lnis-report.xlsx" -> XLSX;
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 통합 산출물입니다: " + fileName);
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(sessionId + "-" + fileName)
                                .build()
                                .toString())
                .body(combinedArtifacts.create(sessionId, fileName));
    }

    @GetMapping("/{sessionId}/artifacts/{role}/{fileName}")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID sessionId,
            @PathVariable String role,
            @PathVariable String fileName) {
        AgentRole agentRole = switch (role.toLowerCase(Locale.ROOT)) {
            case "tx", "sender" -> AgentRole.SENDER;
            case "rx", "receiver" -> AgentRole.RECEIVER;
            default -> throw new IllegalArgumentException("Role must be tx or rx");
        };
        MediaType mediaType = fileName.endsWith(".json") ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv;charset=UTF-8");
        String attachment = sessionId + "-" + role.toLowerCase(Locale.ROOT) + "-" + fileName;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(attachment).build().toString())
                .body(artifacts.create(sessionId, agentRole, fileName));
    }
}
