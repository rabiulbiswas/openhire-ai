package com.openhire.employee.exception;

import com.openhire.employee.service.EmployeeApplicationService.AccessDenied;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
public class EmployeeErrors {
    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ProblemDetail> denied(AccessDenied denied) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, denied.getMessage()));
    }
}
