package com.openhire.platform.web;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(ResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> validation(Exception exception) {
        List<Map<String, String>> errors;
        if (exception instanceof MethodArgumentNotValidException invalid) {
            errors = invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> Map.of(
                            "field",
                            error.getField(),
                            "message",
                            error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                    .toList();
        } else {
            errors = List.of(Map.of("field", "request", "message", exception.getMessage()));
        }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid", errors);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", exception.getMessage(), List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimisticConflict(OptimisticLockingFailureException exception) {
        return problem(
                HttpStatus.PRECONDITION_FAILED, "STALE_WRITE", "The resource changed; reload and retry", List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), List.of());
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, List<Map<String, String>> errors) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://openhire.local/problems/" + code.toLowerCase()));
        problem.setTitle(code);
        problem.setProperty("code", code);
        problem.setProperty(
                "traceId", java.util.Optional.ofNullable(MDC.get("traceId")).orElse("unavailable"));
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).body(problem);
    }
}
