package kr.co.lnis.server.controller;

import jakarta.validation.Valid;
import kr.co.lnis.common.model.LnisModels.SessionSnapshot;
import kr.co.lnis.server.dto.ApiDtos.CreateSessionRequest;
import kr.co.lnis.server.entity.RedisEntities.TestSessionEntity;
import kr.co.lnis.server.service.SessionService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/sessions")
public class SessionController {
    private final SessionService sessions;
    public SessionController(SessionService sessions) { this.sessions = sessions; }
    @PostMapping public TestSessionEntity create(@Valid @RequestBody CreateSessionRequest request) { return sessions.create(request); }
    @GetMapping("/{sessionId}") public SessionSnapshot get(@PathVariable UUID sessionId) { return sessions.snapshot(sessionId); }
    @PostMapping("/{sessionId}/cancel") public TestSessionEntity cancel(@PathVariable UUID sessionId) { return sessions.cancel(sessionId); }
}

