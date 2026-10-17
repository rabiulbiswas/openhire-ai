package com.openhire.analytics.adapter.in.web;

import com.openhire.analytics.application.DeadLetterService.AlreadyReplayed;
import com.openhire.analytics.application.DeadLetterService.NotFound;
import com.openhire.analytics.application.DeadLetterService.RateLimited;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
class AnalyticsErrors {
    @ExceptionHandler(NotFound.class)
    ResponseEntity<ProblemDetail> notFound(NotFound failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, failure.getMessage()));
    }

    @ExceptionHandler(AlreadyReplayed.class)
    ResponseEntity<ProblemDetail> conflict(AlreadyReplayed failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage()));
    }

    @ExceptionHandler(RateLimited.class)
    ResponseEntity<ProblemDetail> limited(RateLimited failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(failure.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, failure.getMessage()));
    }
}
