package com.openhire.identity.controller;

import com.openhire.identity.service.CandidateAccounts;
import com.openhire.platform.security.CandidateIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class CandidateAccountController {
    private final CandidateAccounts accounts;
    private final String secret;
    public CandidateAccountController(CandidateAccounts accounts, @Value("${openhire.internal.secret}") String secret) { this.accounts = accounts; this.secret = secret; }

    @PostMapping("/public/candidate-registrations")
    Object begin(@RequestHeader("X-Public-Tenant-Id") String tenant, @Valid @RequestBody Registration input) {
        return accounts.begin(tenant, input.name(), input.email(), input.location(), input.phone());
    }
    @PostMapping("/public/candidate-registrations/verify")
    Object verify(@RequestHeader("X-Public-Tenant-Id") String tenant, @Valid @RequestBody Verification input) {
        accounts.verify(tenant, input.registrationId(), input.code(), input.password(), input.confirmation());
        return Map.of("registered", true);
    }
    @GetMapping("/candidate/profile")
    Object profile(Authentication auth) { return accounts.profile(CandidateIdentity.require(auth)); }
    @PatchMapping("/candidate/profile")
    Object update(Authentication auth, @Valid @RequestBody ProfileUpdate input) {
        return accounts.update(CandidateIdentity.require(auth), input.name(), input.location(), input.phone());
    }
    @GetMapping("/internal/candidates/{subject}/profile")
    Object internalProfile(@RequestHeader("X-Internal-Service-Secret") String supplied,
            @RequestParam String tenantId, @PathVariable String subject) {
        if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return accounts.profile(tenantId, subject);
    }
    public record Registration(@NotBlank @Size(max=200) String name, @NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(max=200) String location, @NotBlank @Size(max=32) String phone) {}
    public record Verification(@NotBlank @Size(max=200) String registrationId, @Pattern(regexp="[0-9]{6}") @NotBlank String code,
            @NotBlank @Size(min=12,max=128) String password, @NotBlank @Size(max=128) String confirmation) {
        @Override public String toString() { return "CandidateVerification[redacted]"; }
    }
    public record ProfileUpdate(@NotBlank @Size(max=200) String name, @NotBlank @Size(max=200) String location, @NotBlank @Size(max=32) String phone) {}

    @ExceptionHandler(ResponseStatusException.class)
    org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> candidateError(ResponseStatusException error) {
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(error.getStatusCode(),
                error.getReason() == null ? "The request could not be completed" : error.getReason());
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).cacheControl(org.springframework.http.CacheControl.noStore()).body(problem);
    }
}
