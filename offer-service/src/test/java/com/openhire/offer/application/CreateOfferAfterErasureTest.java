package com.openhire.offer.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openhire.offer.application.port.OfferPorts.ApplicationDirectory;
import com.openhire.offer.application.port.OfferPorts.ApplicationInfo;
import com.openhire.offer.application.port.OfferPorts.JobDirectory;
import com.openhire.offer.application.port.OfferPorts.OfferRepository;
import com.openhire.offer.application.port.OfferPorts.OfferTokens;
import com.openhire.offer.application.port.OfferPorts.TemplateStore;
import com.openhire.offer.domain.OfferTypes.Viewer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateOfferAfterErasureTest {
    @Test
    void noOfferIsCreatedForAnErasedCandidate() {
        var offers = mock(OfferRepository.class);
        ApplicationDirectory applications = new ApplicationDirectory() {
            public ApplicationInfo application(String tenantId, String applicationId) {
                return new ApplicationInfo(
                        applicationId, "cand-1", "Erased candidate", null, "job-1", "Engineer", "OFFER");
            }

            public void verifyReceipt(String tenantId, String applicationId, String receipt) {}
        };
        var service = new OfferApplicationService(
                offers,
                mock(TemplateStore.class),
                applications,
                mock(JobDirectory.class),
                mock(OfferTokens.class),
                new OfferSettings(7, Duration.ofDays(30), "http://portal.test", "Northwind", ZoneId.of("UTC")),
                Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("UTC")),
                new AuditProbe().recorder);
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-1", "req-1");
        service.setTombstones(tombstones);

        assertThatThrownBy(() -> service.create(
                        "t1", new Viewer("sub-r", "r@x.test", "Rita", Set.of("RECRUITER")), "app-1", null, null, null))
                .hasMessageContaining("erased");

        verify(offers, never()).save(any());
    }
}
