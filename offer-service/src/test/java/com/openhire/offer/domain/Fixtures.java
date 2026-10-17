package com.openhire.offer.domain;

import com.openhire.offer.domain.OfferTypes.Band;
import com.openhire.offer.domain.OfferTypes.OfferVersion;
import com.openhire.offer.domain.OfferTypes.Terms;
import com.openhire.offer.domain.OfferTypes.Viewer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

final class Fixtures {
    static final Instant NOW = Instant.parse("2026-10-15T05:00:00Z");
    static final Band BAND = new Band("INR", new BigDecimal("20"), new BigDecimal("30")); // midpoint 25

    private Fixtures() {}

    static Terms terms(String base) {
        return new Terms(
                "INR",
                new BigDecimal(base),
                new BigDecimal("2"),
                new BigDecimal("1"),
                "2026-11-16",
                null,
                "Meera Nair",
                "Bengaluru",
                "2026-10-22",
                "Senior Engineer");
    }

    static OfferVersion version(Terms terms) {
        var letter = "Dear Priya Das, base " + terms.base() + "\n";
        return new OfferVersion(
                1,
                "standard",
                1,
                terms,
                "Welcome",
                letter,
                OfferRenderer.sha256(letter),
                "recruiter",
                NOW,
                false,
                false);
    }

    static Offer draft(String base) {
        return Offer.create(
                "t1",
                "app-1",
                "cand-1",
                "Priya Das",
                "job-1",
                "Senior Engineer",
                "recruiter",
                BAND,
                version(terms(base)),
                NOW);
    }

    static Viewer viewer(String subject, String... roles) {
        return new Viewer(subject, subject + "@x.test", subject, Set.of(roles));
    }

    static Viewer hiringManager() {
        return viewer("hm", "HIRING_MANAGER");
    }

    static Viewer hr() {
        return viewer("hr", "HR_ADMIN");
    }

    static Viewer finance() {
        return viewer("fin", "ORG_ADMIN");
    }

    /** Submits and fully approves a draft (a base above 25 adds the Finance step). */
    static Offer approved(String base) {
        var offer = draft(base);
        offer.submit(NOW);
        offer.approve("s1", hiringManager(), NOW);
        offer.approve("s2", hr(), NOW);
        if (offer.status() != OfferTypes.Status.APPROVED) offer.approve("s3", finance(), NOW);
        return offer;
    }

    static Offer sent(String base) {
        var offer = approved(base);
        offer.send(NOW.plusSeconds(7 * 86400), "https://portal/offer#token=t", NOW);
        return offer;
    }
}
