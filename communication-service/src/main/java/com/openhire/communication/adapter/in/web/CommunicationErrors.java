package com.openhire.communication.adapter.in.web;

import com.openhire.communication.application.CommunicationService.Unavailable;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
class CommunicationErrors {
    @ExceptionHandler(Unavailable.class)
    ResponseEntity<ProblemDetail> unavailable(Unavailable unavailable) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, unavailable.getMessage()));
    }
}
