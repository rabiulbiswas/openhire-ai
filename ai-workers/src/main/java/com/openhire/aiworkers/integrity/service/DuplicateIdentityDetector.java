package com.openhire.aiworkers.integrity.service;

import com.openhire.aiworkers.integrity.domain.DetectionContext;
import com.openhire.aiworkers.integrity.domain.Detector;
import com.openhire.aiworkers.integrity.domain.Finding;
import com.openhire.aiworkers.integrity.domain.Identities;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.IdentityIndex;
import java.util.Optional;

/** The same email or phone under a clearly different name elsewhere in the tenant. */
public final class DuplicateIdentityDetector implements Detector {
    private final IdentityIndex index;

    public DuplicateIdentityDetector(IdentityIndex index) {
        this.index = index;
    }

    @Override
    public String type() {
        return "DUPLICATE_IDENTITY";
    }

    @Override
    public double threshold() {
        return 0.6;
    }

    @Override
    public Optional<Finding> detect(DetectionContext context) {
        var tenant = context.tenantId();
        var names = Identities.nameTokens(tenant, context.candidateName());
        if (names.isEmpty()) return Optional.empty();
        boolean email =
                differentName(tenant, "EMAIL", Identities.email(tenant, context.candidateEmail()), context, names);
        boolean phone =
                differentName(tenant, "PHONE", Identities.phone(tenant, context.candidatePhone()), context, names);
        if (!email && !phone) return Optional.empty();
        if (email && phone)
            return Optional.of(
                    new Finding(
                            "HIGH",
                            0.95,
                            "The same email address and phone number appear under a different candidate name in another application."));
        if (email)
            return Optional.of(new Finding(
                    "MEDIUM",
                    0.85,
                    "The same email address appears under a different candidate name in another application."));
        return Optional.of(new Finding(
                "LOW", 0.65, "The same phone number appears under a different candidate name in another application."));
    }

    private boolean differentName(
            String tenant, String kind, String hash, DetectionContext context, java.util.Set<String> names) {
        if (hash.isEmpty()) return false;
        return index.others(tenant, kind, hash, context.candidateId()).stream()
                .anyMatch(other -> !Identities.sameName(names, other.nameTokens()));
    }
}
