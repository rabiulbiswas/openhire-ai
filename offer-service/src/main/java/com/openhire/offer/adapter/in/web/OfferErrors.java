package com.openhire.offer.adapter.in.web;

import com.openhire.offer.application.OfferApplicationService.AccessDenied;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
class OfferErrors {
    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ProblemDetail> denied(AccessDenied denied) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, denied.getMessage()));
    }

    /** Two people changed the same offer at once; the loser is told to look again. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> conflict(OptimisticLockingFailureException conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "The offer was changed by someone else. Reload and try again."));
    }
}
