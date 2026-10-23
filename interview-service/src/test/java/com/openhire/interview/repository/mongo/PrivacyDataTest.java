package com.openhire.interview.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.openhire.interview.service.port.InterviewPorts.SlotHolds;
import com.openhire.support.privacy.PrivacyParticipant.Status;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivacyDataTest {
    private final InMemoryMongo mongo = new InMemoryMongo();
    private final SlotHolds holds = mock(SlotHolds.class);
    private final com.openhire.support.privacy.Tombstones tombstones = InMemoryMongo.tombstones();
    private final PrivacyData data = new PrivacyData(mongo.template, holds, tombstones);

    private static Document loop(String id, String tenant, String candidate, String name) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("applicationId", "app-" + id)
                .append("candidateId", candidate)
                .append("candidateName", name)
                .append("jobTitle", "Engineer")
                .append("revision", 1L)
                .append(
                        "rounds",
                        List.of(new Document("roundId", id + "_r1")
                                .append("name", "Technical")
                                .append("status", "COMPLETED")
                                .append("slot", new Document("timezone", "UTC"))
                                .append("meetingUrl", "https://meet.example.test/secret")
                                .append("panel", List.of(new Document("name", "Asha").append("email", "Asha@x.test")))
                                .append(
                                        "scorecards",
                                        List.of(new Document("interviewerKey", "email:asha@x.test")
                                                .append("interviewerName", "Asha")
                                                .append("evidence", "Explained the design well")
                                                .append("recommendation", "YES")
                                                .append(
                                                        "ratings",
                                                        List.of(new Document("competencyId", "c1")
                                                                .append("rating", "STRONG")))))))
                .append(
                        "_events",
                        List.of(new Document("eventId", "e1")
                                .append(
                                        "payload",
                                        new Document("roundId", id + "_r1")
                                                .append("meetingUrl", "https://meet.example.test/secret"))));
    }

    @BeforeEach
    void rows() {
        mongo.put("interview_loops", loop("l1", "t1", "cand-1", "Priya Das"));
        mongo.put("interview_loops", loop("l2", "t1", "cand-2", "Ben Other"));
        mongo.put("interview_loops", loop("l3", "t2", "cand-1", "Priya Das"));
    }

    @Test
    void exportShowsTheCandidatesScorecardsWithoutInterviewerIdsOrLinks() {
        var section = data.export("t1", "cand-1");

        var interviews = (List<?>) section.get("interviews");
        assertThat(interviews).hasSize(1);
        var text = section.toString();
        assertThat(text).contains("Explained the design well").contains("Priya Das");
        assertThat(text)
                .doesNotContain("secret")
                .doesNotContain("interviewerKey")
                .doesNotContain("asha@x.test");
        assertThat(text).doesNotContain("Ben Other");
        var round = (Map<?, ?>) ((List<?>) ((Map<?, ?>) interviews.getFirst()).get("rounds")).getFirst();
        assertThat((List<?>) round.get("scorecards")).hasSize(1);
    }

    @Test
    void erasureRemovesScorecardsNameAndLinksButKeepsRoundsAndStatuses() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts()).containsEntry("interviewLoops", 1).containsEntry("scorecards", 1);
        var loop = mongo.one("interview_loops", "l1");
        assertThat(loop.getString("candidateName")).isEqualTo("Erased candidate");
        var round = loop.getList("rounds", Document.class).getFirst();
        assertThat(round.getString("roundId")).isEqualTo("l1_r1");
        assertThat(round.getString("status")).isEqualTo("COMPLETED");
        assertThat(round.getList("scorecards", Document.class)).isEmpty();
        assertThat(round).doesNotContainKey("meetingUrl");
        var payload =
                (Document) loop.getList("_events", Document.class).getFirst().get("payload");
        assertThat(payload).doesNotContainKey("meetingUrl").containsKey("roundId");
        verify(holds).clear(eq("t1"), eq("l1_r1"), eq(List.of("email:asha@x.test")));
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        for (var id : List.of("l2", "l3")) {
            var loop = mongo.one("interview_loops", id);
            assertThat(loop.getString("candidateName")).isNotEqualTo("Erased candidate");
            assertThat(loop.getList("rounds", Document.class).getFirst().getList("scorecards", Document.class))
                    .hasSize(1);
        }
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(again.counts().values()).containsOnly(0);
    }
}
