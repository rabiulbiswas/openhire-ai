package com.openhire.approval.api;

import com.openhire.approval.adapter.out.mongo.DelegationPeriodRepository;
import com.openhire.approval.domain.DelegationPeriod;
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    DelegationPeriod create(
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody Request request) {
        return periods.save(DelegationPeriod.create(
                TenantContext.requiredTenantId(), actor, request.delegateId(), request.startsAt(), request.endsAt()));
    }

    public record Request(@NotBlank String delegateId, @NotNull Instant startsAt, @NotNull Instant endsAt) {}
}
