package kr.co.lnis.server.controller;

import kr.co.lnis.common.model.LnisModels.AgentRole;
import kr.co.lnis.server.service.ArtifactService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/sessions")
public class ArtifactController {
    private final ArtifactService artifacts;
    public ArtifactController(ArtifactService artifacts) { this.artifacts = artifacts; }
    @GetMapping("/{sessionId}/artifacts/{role}/{fileName}") public ResponseEntity<byte[]> download(@PathVariable UUID sessionId, @PathVariable String role, @PathVariable String fileName) {
        AgentRole agentRole = switch (role.toLowerCase(Locale.ROOT)) { case "tx", "sender" -> AgentRole.SENDER; case "rx", "receiver" -> AgentRole.RECEIVER; default -> throw new IllegalArgumentException("Role must be tx or rx"); };
        MediaType mediaType = fileName.endsWith(".json") ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv;charset=UTF-8");
        String attachment = sessionId + "-" + role.toLowerCase(Locale.ROOT) + "-" + fileName;
        return ResponseEntity.ok().contentType(mediaType).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(attachment).build().toString()).body(artifacts.create(sessionId, agentRole, fileName));
    }
}

