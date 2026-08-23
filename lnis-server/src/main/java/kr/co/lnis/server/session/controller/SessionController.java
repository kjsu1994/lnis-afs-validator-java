package kr.co.lnis.server.session.controller;

import jakarta.validation.Valid;
import kr.co.lnis.protocol.model.LnisModels.SessionSnapshot;
import kr.co.lnis.server.session.dto.CreateSessionRequest;
import kr.co.lnis.server.session.entity.TestSessionEntity;
import kr.co.lnis.server.session.service.SessionService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/sessions")
/** Sender/Receiver 시험 세션의 생성, 조회 및 취소 API를 제공한다. */
public class SessionController {
    private final SessionService sessions;
    public SessionController(SessionService sessions) { this.sessions = sessions; }
    @PostMapping
    public TestSessionEntity create(@Valid @RequestBody CreateSessionRequest request) {
        return sessions.create(request);
    }

    @GetMapping("/{sessionId}")
    public SessionSnapshot get(@PathVariable UUID sessionId) {
        return sessions.snapshot(sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    public TestSessionEntity cancel(@PathVariable UUID sessionId) {
        return sessions.cancel(sessionId);
    }
}
