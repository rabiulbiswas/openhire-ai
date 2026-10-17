package com.openhire.offer.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.support.privacy.PrivacyParticipant.Status;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivacyDataTest {
    private final InMemoryMongo mongo = new InMemoryMongo();
    private final com.openhire.support.privacy.Tombstones tombstones = InMemoryMongo.tombstones();
    private final PrivacyData data = new PrivacyData(mongo.template, tombstones);

    private static Document offer(String id, String tenant, String candidate, String status) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("applicationId", "app-" + id)
                .append("candidateId", candidate)
                .append("candidateName", "Priya Das")
                .append("jobTitle", "Engineer")
                .append("status", status)
                .append("candidateNote", "Can we discuss the joining date?")
                .append("responseReason", "Took another job")
                .append("revision", 2L)
                .append(
                        "versions",
                        List.of(new Document("versionNo", 1)
                                .append("terms", new Document("base", 100).append("designation", "SE"))
                                .append("coverNote", "Welcome Priya")
                                .append("letterText", "Dear Priya Das, we offer you")
                                .append("sha256", "digest")
                                .append("createdBy", "staff-1")))
                .append(
                        "_events",
                        List.of(new Document("eventId", "e1")
                                .append("payload", new Document("offerId", id).append("message", "Can we discuss?"))));
    }

    @BeforeEach
    void rows() {
        mongo.put("offers", offer("o1", "t1", "cand-1", "DECLINED"));
        mongo.put("offers", offer("o2", "t1", "cand-1", "ACCEPTED"));
        mongo.put("offers", offer("o3", "t1", "cand-2", "DECLINED"));
        mongo.put("offers", offer("o4", "t2", "cand-1", "DECLINED"));
        mongo.put("offers", offer("o5", "t1", "cand-3", "SENT"));
    }

    @Test
    void exportShowsTheLettersWithoutHashesOrStaffIds() {
        var section = data.export("t1", "cand-1");

        var offers = (List<?>) section.get("offers");
        assertThat(offers).hasSize(2);
        var text = section.toString();
        assertThat(text).contains("Dear Priya Das").contains("Welcome Priya");
        assertThat(text).doesNotContain("digest").doesNotContain("staff-1");
        assertThat(((Map<?, ?>) offers.getFirst()).get("candidateNote")).isEqualTo("Can we discuss the joining date?");
    }

    @Test
    void anAcceptedOfferIsRetainedAndTheOthersAreAnonymised() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.RETAINED_LEGAL_BASIS);
        assertThat(outcome.note()).isEqualTo("Accepted offers are kept as part of the employment record");
        assertThat(outcome.counts())
                .containsEntry("acceptedOffersRetained", 1)
                .containsEntry("offersAnonymised", 1)
                .containsEntry("letters", 1);
        var accepted = mongo.one("offers", "o2");
        assertThat(accepted.getString("candidateName")).isEqualTo("Priya Das");
        assertThat(accepted.getList("versions", Document.class).getFirst().getString("letterText"))
                .isNotNull();
        var declined = mongo.one("offers", "o1");
        assertThat(declined.getString("candidateName")).isEqualTo("Erased candidate");
        assertThat(declined).doesNotContainKeys("candidateNote", "responseReason");
        var version = declined.getList("versions", Document.class).getFirst();
        assertThat(version).doesNotContainKeys("letterText", "coverNote");
        assertThat(((Document) version.get("terms")).getInteger("base")).isEqualTo(100);
        var payload = (Document)
                declined.getList("_events", Document.class).getFirst().get("payload");
        assertThat(payload).doesNotContainKey("message").containsKey("offerId");
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void withoutAnAcceptedOfferTheOutcomeIsErased() {
        var outcome = data.erase("t1", "req-1", "cand-3");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(mongo.one("offers", "o5").getString("candidateName")).isEqualTo("Erased candidate");
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        for (var id : List.of("o3", "o4", "o5")) {
            assertThat(mongo.one("offers", id).getString("candidateName")).isEqualTo("Priya Das");
            assertThat(mongo.one("offers", id).getString("candidateNote")).isNotNull();
        }
    }

    @Test
    void erasingTwiceChangesNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-3");

        var again = data.erase("t1", "req-1", "cand-3");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        var withAccepted = data.erase("t1", "req-2", "cand-1");
        var repeated = data.erase("t1", "req-2", "cand-1");
        assertThat(withAccepted.counts()).containsEntry("offersAnonymised", 1);
        assertThat(repeated.status()).isEqualTo(Status.RETAINED_LEGAL_BASIS);
        assertThat(repeated.counts()).containsEntry("offersAnonymised", 0).containsEntry("acceptedOffersRetained", 1);
    }
}
