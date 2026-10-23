package com.openhire.compliance.exception;

import com.openhire.compliance.exception.ComplianceExceptions.Conflict;
import com.openhire.compliance.exception.ComplianceExceptions.Forbidden;
import com.openhire.compliance.exception.ComplianceExceptions.Gone;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
public class ComplianceErrors {
    @ExceptionHandler(Forbidden.class)
    ResponseEntity<ProblemDetail> forbidden(Forbidden e) {
        return problem(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(Gone.class)
    ResponseEntity<ProblemDetail> gone(Gone e) {
        return problem(HttpStatus.GONE, e);
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<ProblemDetail> conflict(Conflict e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, e.getMessage()));
    }
}
