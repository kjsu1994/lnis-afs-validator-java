package server.session.controller;

import jakarta.validation.Valid;
import server.model.LnisModels.SessionSnapshot;
import server.session.dto.CreateSessionRequest;
import server.session.entity.TestSessionEntity;
import server.session.service.SessionService;
import org.springframework.http.ResponseEntity;
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

    /** 페이지를 새로 열어도 현재 시험을 복원하고 취소할 수 있도록 활성 세션을 반환한다. */
    @GetMapping("/active")
    public ResponseEntity<SessionSnapshot> active() {
        return sessions.activeSnapshot()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
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
