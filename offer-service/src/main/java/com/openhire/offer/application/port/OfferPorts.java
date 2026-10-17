package com.openhire.offer.application.port;

import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferClause;
import com.openhire.offer.domain.OfferTemplate;
import com.openhire.offer.domain.OfferTypes.Band;
import com.openhire.offer.domain.OfferTypes.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class OfferPorts {
    private OfferPorts() {}

    public interface OfferRepository {
        Offer save(Offer offer);

        Optional<Offer> find(String tenantId, String offerId);

        Optional<Offer> findByApplication(String tenantId, String applicationId);

        List<Offer> list(String tenantId, Status status, int limit);

        List<Offer> findExpired(Instant now, int limit);
    }

    public interface TemplateStore {
        Optional<OfferTemplate> template(String tenantId, String key);

        List<OfferTemplate> templates(String tenantId);

        OfferTemplate saveTemplate(OfferTemplate template);

        Optional<OfferClause> clause(String tenantId, String key);

        List<OfferClause> clauses(String tenantId);

        OfferClause saveClause(OfferClause clause);

        /** Inserts the default only when the key does not exist yet; never overwrites a tenant's edits. */
        void seedTemplate(OfferTemplate template);

        void seedClause(OfferClause clause);
    }

    public record ApplicationInfo(
            String applicationId,
            String candidateId,
            String candidateName,
            String candidateEmail,
            String jobId,
            String jobTitle,
            String stage) {}

    public interface ApplicationDirectory {
        ApplicationInfo application(String tenantId, String applicationId);

        /** Throws a 403 ResponseStatusException when the receipt does not match the application. */
        void verifyReceipt(String tenantId, String applicationId, String receipt);
    }

    public record JobInfo(String jobId, String title, String location, String hiringManager, Band band) {}

    public interface JobDirectory {
        JobInfo job(String tenantId, String jobId);
    }

    public record TokenClaims(String tenantId, String offerId, Instant expiresAt) {}

    public interface OfferTokens {
        String issue(String tenantId, String offerId, Instant expiresAt);

        TokenClaims verify(String token, Instant now);
    }

    public interface SweepLock {
        boolean tryAcquire(String name, Duration lease);
    }
}
