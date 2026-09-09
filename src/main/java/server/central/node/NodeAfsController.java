package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import server.central.frameevidence.FrameEvidenceEntity;
import server.central.frameevidence.FrameEvidenceRepository;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.FrameEvidenceMessage;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.SessionSnapshot;

import java.util.List;
import java.util.UUID;

@RestController
@Profile("node")
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/node/peer/afs")
public class NodeAfsController {
    private final NodeAuthenticationService authentication;
    private final NodeAfsService service;
    private final FrameEvidenceRepository evidenceRepository;
    private final ObjectMapper mapper;

    @PostMapping("/commands")
    public ResponseEntity<SessionSnapshot> command(HttpServletRequest request) throws Exception
    {
        authentication.authenticate(request.getHeader("Authorization"));
        byte[] body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        Envelope envelope = mapper.readValue(body, Envelope.class);
        return new ResponseEntity<>(service.command(envelope), HttpStatus.OK);
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<SessionSnapshot> result(@PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authorization)
    {
        authentication.authenticate(authorization);
        return new ResponseEntity<>(service.result(id), HttpStatus.OK);
    }

    /** 완료된 AFS 시험의 분석 자료만 페이지로 조회한다. DTN 프레임은 이 저장소에 존재하지 않는다. */
    @GetMapping("/sessions/{id}/evidence")
    public ResponseEntity<List<FrameEvidenceMessage>> evidence(@PathVariable UUID id,
            @RequestParam(defaultValue = "-1") int after,
            @RequestHeader(value = "Authorization", required = false) String authorization)
    {
        authentication.authenticate(authorization);
        service.result(id);
        if (after < -1) {
            throw new IllegalArgumentException("잘못된 프레임 커서입니다.");
        }
        List<FrameEvidenceMessage> response = evidenceRepository.findAll(id).stream()
                .filter(item -> item.role() == AgentRole.RECEIVER && item.frameIndex() > after)
                .sorted(java.util.Comparator.comparingInt(FrameEvidenceEntity::frameIndex))
                .limit(32).map(FrameEvidenceEntity::evidence).toList();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
