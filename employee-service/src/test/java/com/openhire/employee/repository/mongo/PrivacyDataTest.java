package com.openhire.employee.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.support.privacy.PrivacyParticipant.Status;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivacyDataTest {
    private final InMemoryMongo mongo = new InMemoryMongo();
    private final PrivacyData data = new PrivacyData(mongo.template);

    private static Document saga(String id, String tenant, String candidate, String employeeId) {
        var saga = new Document("_id", id)
                .append("tenantId", tenant)
                .append("candidateId", candidate)
                .append("candidateName", "Priya Das")
                .append("jobTitle", "Engineer")
                .append("revision", 1L)
                .append(
                        "snapshot",
                        new Document("name", "Priya Das")
                                .append("email", "p@example.test")
                                .append("actorId", "u1"));
        if (employeeId != null) saga.append("employeeId", employeeId);
        return saga;
    }

    @BeforeEach
    void rows() {
        mongo.put(
                "employees",
                new Document("_id", "e1")
                        .append("tenantId", "t1")
                        .append("candidateId", "cand-1")
                        .append("employeeNo", "E-001")
                        .append("name", "Priya Das")
                        .append("email", "p@example.test")
                        .append("hrisRequests", 2)
                        .append("buddy", new Document("name", "Other Person").append("email", "o@example.test"))
                        .append(
                                "tasks",
                                List.of(new Document("id", "t")
                                        .append("label", "Laptop")
                                        .append("done", true))));
        mongo.put(
                "employees",
                new Document("_id", "e2")
                        .append("tenantId", "t1")
                        .append("candidateId", "cand-2")
                        .append("name", "Ben Other"));
        mongo.put("hire_sagas", saga("s1", "t1", "cand-1", "e1"));
        mongo.put("hire_sagas", saga("s2", "t1", "cand-1", null));
        mongo.put("hire_sagas", saga("s3", "t1", "cand-2", "e2"));
        mongo.put("hire_sagas", saga("s4", "t2", "cand-1", "e9"));
    }

    @Test
    void exportShowsTheEmployeeRecordWithoutOtherPeople() {
        var section = data.export("t1", "cand-1");

        var employees = (List<?>) section.get("employees");
        assertThat(employees).hasSize(1);
        assertThat(((Map<?, ?>) employees.getFirst()).get("employeeNo")).isEqualTo("E-001");
        assertThat(section.toString())
                .doesNotContain("Other Person")
                .doesNotContain("Ben Other")
                .doesNotContain("u1");
    }

    @Test
    void anEmployeeRecordIsRetainedAndOnlyCompletedSagasLoseTheirCopies() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.RETAINED_LEGAL_BASIS);
        assertThat(outcome.note())
                .isEqualTo(
                        "Employment record retained on a legal basis; applicant-stage data was erased by the other services");
        assertThat(outcome.counts())
                .containsEntry("employeeRecordsRetained", 1)
                .containsEntry("hireSagasAnonymised", 1);
        assertThat(mongo.one("employees", "e1").getString("email")).isEqualTo("p@example.test");
        assertThat(mongo.one("employees", "e1").getString("name")).isEqualTo("Priya Das");
        var done = mongo.one("hire_sagas", "s1");
        assertThat(done.getString("candidateName")).isEqualTo("Erased candidate");
        assertThat(done).doesNotContainKey("snapshot");
        var inFlight = mongo.one("hire_sagas", "s2");
        assertThat(inFlight.getString("candidateName")).isEqualTo("Priya Das");
        assertThat(inFlight).containsKey("snapshot");
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        for (var id : List.of("s3", "s4"))
            assertThat(mongo.one("hire_sagas", id).getString("candidateName")).isEqualTo("Priya Das");
        assertThat(mongo.one("employees", "e2").getString("name")).isEqualTo("Ben Other");
    }

    @Test
    void aCandidateWhoWasNeverHiredHoldsNothing() {
        assertThat(data.erase("t1", "req-1", "nobody").status()).isEqualTo(Status.NOTHING_HELD);
    }

    @Test
    void erasingTwiceDoesNotAnonymiseAgain() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.RETAINED_LEGAL_BASIS);
        assertThat(again.counts()).containsEntry("hireSagasAnonymised", 0);
    }
}
