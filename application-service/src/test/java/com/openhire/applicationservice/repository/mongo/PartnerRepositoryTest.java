package com.openhire.applicationservice.repository.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class PartnerRepositoryTest {
    @Test
    void employeePagesAreScopedToOwnReferralAndInternalSources() {
        var mongo = mock(MongoTemplate.class);
        var repository = new PartnerRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("submissions")))
                .thenReturn(List.of());
        repository.employeeSubmissions("tenant-a", "employee-a", "", "", 10);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Document.class), eq("submissions"));
        assertThat(query.getValue().getQueryObject().toString())
                .contains("tenant-a", "employee-a", "REFERRAL", "INTERNAL")
                .doesNotContain("AGENCY");
        assertThat(query.getValue().getLimit()).isEqualTo(11);
    }

    @Test
    void submissionsUseBoundedKeysetPagingWithTenantActorAndAgencyScope() {
        var mongo = mock(MongoTemplate.class);
        var repository = new PartnerRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("submissions")))
                .thenReturn(List.of(
                        new Document("_id", "c").append("submittedAt", "2026-10-25T00:00:00Z"),
                        new Document("_id", "b").append("submittedAt", "2026-10-25T00:00:00Z"),
                        new Document("_id", "a").append("submittedAt", "2026-10-25T00:00:00Z")));
        var first = repository.submissions("tenant-a", "actor-a", "", "", 2);
        assertThat(first.data()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        var capture = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(capture.capture(), eq(Document.class), eq("submissions"));
        var query = capture.getValue();
        assertThat(query.getLimit()).isEqualTo(3);
        assertThat(query.getSkip()).isZero();
        assertThat(query.getQueryObject().toString()).contains("tenant-a", "actor-a", "AGENCY");
        assertThat(query.getSortObject()).isEqualTo(new Document("submittedAt", -1).append("_id", -1));
        assertThat(query.getFieldsObject()).doesNotContainKeys("assessment", "score", "detail");
        repository.submissions("tenant-a", "actor-a", first.nextCursor(), "", 2);
        verify(mongo, atLeast(2)).find(capture.capture(), eq(Document.class), eq("submissions"));
        assertThat(capture.getAllValues())
                .anySatisfy(value -> assertThat(value.getQueryObject().toString())
                        .contains("$lt", "submittedAt", "tenant-a", "actor-a"));
        assertThatThrownBy(() -> repository.jobs("t", "a", "bad", "", 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.jobs("t", "a", "a", "b", 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.jobs("t", "a", "", "", 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignedJobsAreScopedAndOnlyActiveAssignmentsAreListed() {
        var mongo = mock(MongoTemplate.class);
        var repository = new PartnerRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("agency_assignments")))
                .thenReturn(List.of());
        repository.jobs("tenant-a", "actor-a", "", "", 10);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Document.class), eq("agency_assignments"));
        assertThat(query.getValue().getQueryObject().toString()).contains("tenant-a", "actor-a", "active=true");
        assertThat(query.getValue().getLimit()).isEqualTo(11);
    }
}
