package kr.co.lnis.server.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestControllerAdvice
/** API 예외를 일관된 RFC 9457 ProblemDetail 응답으로 변환한다. */
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, error, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(Exception error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, error, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, Exception error, HttpServletRequest request) {
        String message = error.getMessage() == null
                ? status.getReasonPhrase()
                : error.getMessage();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("code", status.name());
        return ResponseEntity.status(status).body(detail);
    }
}
