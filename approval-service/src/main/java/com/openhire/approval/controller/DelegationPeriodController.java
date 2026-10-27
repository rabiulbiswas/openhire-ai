package com.openhire.approval.controller;

import com.openhire.approval.domain.DelegationPeriod;
import com.openhire.approval.repository.mongo.DelegationPeriodRepository;
import com.openhire.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approval-delegation-periods")
public class DelegationPeriodController {
    private final DelegationPeriodRepository periods;

    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.platform.security.TenantStaffDirectory staff;

    public DelegationPeriodController(DelegationPeriodRepository periods) {
        this.periods = periods;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    List<DelegationPeriod> list(@RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return periods.findByTenantIdAndOwnerIdOrderByStartsAtDesc(TenantContext.requiredTenantId(), actor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','HR_ADMIN','HIRING_MANAGER')")
    DelegationPeriod create(
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody Request request) {
        var tenant = TenantContext.requiredTenantId();
        var people = staff.members(tenant);
        var owner = people.stream()
                .filter(m -> m.id().equals(actor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active tenant membership required"));
        var target = people.stream()
                .filter(m -> m.id().equals(request.delegateId()) && !m.id().equals(actor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose another active approver in this organisation"));
        var roles = owner.roles().stream()
                .filter(java.util.Set.of("HIRING_MANAGER", "HR_ADMIN", "ORG_ADMIN")::contains)
                .toList();
        if (roles.isEmpty() || !target.roles().containsAll(roles))
            throw new IllegalArgumentException("The delegate must hold the same approval roles");
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth
                        instanceof
                        org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt
                && !actor.equals(jwt.getName()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        return periods.save(DelegationPeriod.create(
                TenantContext.requiredTenantId(), actor, request.delegateId(), request.startsAt(), request.endsAt()));
    }

    public record Request(@NotBlank String delegateId, @NotNull Instant startsAt, @NotNull Instant endsAt) {}
}
