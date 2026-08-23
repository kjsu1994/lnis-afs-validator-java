package kr.co.lnis.server.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception error, HttpServletRequest request) { return problem(HttpStatus.BAD_REQUEST, error, request); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(Exception error, HttpServletRequest request) { return problem(HttpStatus.CONFLICT, error, request); }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, Exception error, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, error.getMessage() == null ? status.getReasonPhrase() : error.getMessage());
        detail.setTitle(status.getReasonPhrase()); detail.setInstance(URI.create(request.getRequestURI())); detail.setProperty("code", status.name()); return ResponseEntity.status(status).body(detail);
    }
}

