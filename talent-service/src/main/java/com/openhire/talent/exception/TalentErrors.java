package com.openhire.talent.exception;

import com.openhire.talent.service.InvitationService.AccessDenied;
import com.openhire.talent.service.port.TalentPorts.ReceiptRejected;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
public class TalentErrors {
    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ProblemDetail> denied(AccessDenied denied) {
        return forbidden(denied.getMessage());
    }

    @ExceptionHandler(ReceiptRejected.class)
    ResponseEntity<ProblemDetail> receipt(ReceiptRejected rejected) {
        return forbidden(rejected.getMessage());
    }

    private static ResponseEntity<ProblemDetail> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, message));
    }
}
