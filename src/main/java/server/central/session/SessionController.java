package server.central.session;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import server.shared.model.LnisModels.SessionSnapshot;

import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/lnis/api/v1/sessions")
/** Sender/Receiver 시험 세션의 생성, 조회 및 취소 API를 제공한다. */
public class SessionController {
    private final SessionService sessionService;

    /* 시험 세션 생성 및 Sender/Receiver 시작 요청 */
    @PostMapping
    public ResponseEntity<TestSessionEntity> create(
            @Valid @RequestBody CreateSessionRequest request)
    {
        TestSessionEntity response = sessionService.create(request);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /** 페이지를 새로 열어도 현재 시험을 복원하고 취소할 수 있도록 활성 세션을 반환한다. */
    @GetMapping("/active")
    public ResponseEntity<SessionSnapshot> active()
    {
        return sessionService
                .activeSnapshot()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /* 시험 세션 상태 조회 */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionSnapshot> get(@PathVariable UUID sessionId)
    {
        SessionSnapshot response = sessionService.snapshot(sessionId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 시험 취소 및 양쪽 Agent 종료 요청 */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<TestSessionEntity> cancel(@PathVariable UUID sessionId)
    {
        TestSessionEntity response = sessionService.cancel(sessionId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
