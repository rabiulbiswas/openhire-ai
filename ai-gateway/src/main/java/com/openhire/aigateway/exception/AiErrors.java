package com.openhire.aigateway.exception;

import com.openhire.aigateway.exception.AiExceptions.AccessDenied;
import com.openhire.aigateway.exception.AiExceptions.AiInvalidResponse;
import com.openhire.aigateway.exception.AiExceptions.AiTimeout;
import com.openhire.aigateway.exception.AiExceptions.AiUnavailable;
import com.openhire.aigateway.exception.AiExceptions.BudgetExhausted;
import com.openhire.aigateway.exception.AiExceptions.EvaluationBusy;
import com.openhire.aigateway.exception.AiExceptions.PromotionBlocked;
import com.openhire.aigateway.exception.AiExceptions.ValidationFailed;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problems with a stable {@code code}; callers branch on AI_UNAVAILABLE and AI_BUDGET_EXHAUSTED. */
@RestControllerAdvice
@Order(0)
public class AiErrors {
    @ExceptionHandler(AiUnavailable.class)
    ResponseEntity<ProblemDetail> unavailable(AiUnavailable e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(AiTimeout.class)
    ResponseEntity<ProblemDetail> timeout(AiTimeout e) {
        return problem(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", e.getMessage());
    }

    @ExceptionHandler(AiInvalidResponse.class)
    ResponseEntity<ProblemDetail> invalidResponse(AiInvalidResponse e) {
        return problem(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE", e.getMessage());
    }

    @ExceptionHandler(BudgetExhausted.class)
    ResponseEntity<ProblemDetail> budget(BudgetExhausted e) {
        return problem(HttpStatus.PAYMENT_REQUIRED, "AI_BUDGET_EXHAUSTED", e.getMessage());
    }

    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ProblemDetail> denied(AccessDenied e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(EvaluationBusy.class)
    ResponseEntity<ProblemDetail> busy(EvaluationBusy e) {
        return problem(HttpStatus.CONFLICT, "EVALUATION_BUSY", e.getMessage());
    }

    @ExceptionHandler(PromotionBlocked.class)
    ResponseEntity<ProblemDetail> blocked(PromotionBlocked e) {
        var body = problem(HttpStatus.CONFLICT, "PROMOTION_BLOCKED", e.getMessage());
        body.getBody().setProperty("failedGates", e.failedGates());
        return body;
    }

    @ExceptionHandler(ValidationFailed.class)
    ResponseEntity<ProblemDetail> invalid(ValidationFailed e) {
        var body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage());
        if (!e.errors().isEmpty())
            body.getBody()
                    .setProperty(
                            "errors",
                            e.errors().stream()
                                    .map(f -> java.util.Map.of("field", f.field(), "message", f.message()))
                                    .toList());
        return body;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://openhire.local/problems/" + code.toLowerCase()));
        problem.setTitle(code);
        problem.setProperty("code", code);
        problem.setProperty(
                "traceId", java.util.Optional.ofNullable(MDC.get("traceId")).orElse("unavailable"));
        return ResponseEntity.status(status).body(problem);
    }
}
