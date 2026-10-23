package com.openhire.interview.exception;

import com.openhire.interview.service.InterviewApplicationService.AccessDenied;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
public class InterviewErrors {
    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ProblemDetail> denied(AccessDenied denied) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, denied.getMessage()));
    }
}
