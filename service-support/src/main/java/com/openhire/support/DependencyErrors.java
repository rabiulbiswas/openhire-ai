package com.openhire.support;

import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Do not expose internal addresses, upstream credentials or stack traces in browser responses. */
@RestControllerAdvice
@Order(0)
public class DependencyErrors {
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ProblemDetail> unavailable(RestClientException failure) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        String message = "A required service is unavailable; retry this request with the same idempotency key.";
        if (failure instanceof RestClientResponseException response) {
            if (response.getStatusCode().value() == 400
                    || response.getStatusCode().value() == 413) {
                status = HttpStatus.BAD_REQUEST;
                message =
                        "A required service rejected the submitted data or file. Check the supported file format and size.";
            } else if (response.getStatusCode().value() == 404) {
                status = HttpStatus.NOT_FOUND;
                message = "The referenced record is unavailable.";
            } else if (response.getStatusCode().value() == 409) {
                status = HttpStatus.CONFLICT;
                message = "A related record changed or cannot be processed in its current state.";
            }
        }
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message));
    }
}
