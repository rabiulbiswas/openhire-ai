package com.openhire.candidateservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class CandidateIsolationTest {
    @Test
    void directoryReturnsOnlySmallApplicationStatusSummariesWithoutCollapsingDifferentJobs() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Document.class), eq("candidates"))).thenReturn(java.util.List.of(
                new Document("_id", "candidate").append("applications", new Document()
                        .append("older", new Document("applicationId", "a").append("jobId", "job-a")
                                .append("jobTitle", "Backend Engineer").append("stage", "REJECTED")
                                .append("appliedAt", "2026-01-01").append("privateNotes", "private"))
                        .append("newer", new Document("applicationId", "b").append("jobId", "job-b")
                                .append("jobTitle", "Data Engineer").append("stage", "SHORTLISTED")
                                .append("appliedAt", "2026-02-01")))));
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        var rows = (java.util.List<?>) service.list("tenant-a", null, null, 25).get("data");
        var applications = (java.util.List<?>) ((Document) rows.getFirst()).get("applicationStatuses");
        assertThat(applications).hasSize(2);
        assertThat((Document) applications.getFirst()).containsEntry("stage", "SHORTLISTED").containsEntry("jobId", "job-b");
        assertThat((Document) applications.getLast()).containsEntry("stage", "REJECTED")
                .containsOnlyKeys("applicationId", "jobId", "jobTitle", "stage");
    }

    @Test
    void directoryIsTenantScopedBoundedAndDoesNotExposeProfileData() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Document.class), eq("candidates")))
                .thenReturn(java.util.List.of(
                        new Document("_id", "real-a").append("name", "Ada").append("phone", "private"),
                        new Document("_id", "real-b")));
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        var result = service.list("tenant-a", "a.*", "previous", 1);
        assertThat(result).containsEntry("hasMore", true).containsEntry("nextCursor", "real-a");
        var rows = (java.util.List<?>) result.get("data");
        assertThat((Document) rows.getFirst()).containsEntry("id", "real-a").doesNotContainKeys("phone", "_id");
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Document.class), eq("candidates"));
        assertThat(query.getValue().getQueryObject())
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("_id", new Document("$gt", "previous"))
                .containsEntry("mergedInto", new Document("$exists", false))
                .containsEntry("erased", new Document("$ne", true));
        assertThat(query.getValue().getQueryObject().toString()).contains("\\Q" + "a.*" + "\\E");
        assertThat(query.getValue().getLimit()).isEqualTo(2);
        assertThat(query.getValue().getSortObject()).containsEntry("_id", 1);
        verify(mongo).exists(any(Query.class), eq("candidates"));
        verifyNoMoreInteractions(mongo);
    }

    @Test
    void emptyDirectoryHasNoInventedCandidateOrCursor() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Document.class), eq("candidates"))).thenReturn(java.util.List.of());
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        assertThat(service.list("tenant-a", null, null, 25))
                .containsEntry("data", java.util.List.of())
                .containsEntry("hasMore", false)
                .containsEntry("nextCursor", null);
    }

    @Test
    void directoryRejectsInvalidInputsBeforeQuerying() {
        var mongo = mock(MongoTemplate.class);
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        assertThatThrownBy(() -> service.list("", null, null, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("tenant-a", null, null, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("tenant-a", "x".repeat(201), null, 25))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("tenant-a", null, "x".repeat(201), 25))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("tenant-a", null, "a", "b", 25))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("tenant-a", null, null, "x".repeat(201), 25))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mongo);
    }

    @Test
    void backwardsPaginationReadsInReverseButReturnsStableAscendingRows() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Document.class), eq("candidates")))
                .thenReturn(java.util.List.of(new Document("_id", "c"), new Document("_id", "b"), new Document("_id", "a")));
        when(mongo.exists(any(Query.class), eq("candidates"))).thenReturn(true);
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        var result = service.list("tenant-a", "Ada", null, "d", 2);
        var rows = (java.util.List<?>) result.get("data");
        assertThat((Document) rows.getFirst()).containsEntry("id", "b");
        assertThat((Document) rows.getLast()).containsEntry("id", "c");
        assertThat(result).containsEntry("hasPrevious", true).containsEntry("previousCursor", "b")
                .containsEntry("hasMore", true).containsEntry("nextCursor", "c").containsEntry("limit", 2);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Document.class), eq("candidates"));
        assertThat(query.getValue().getQueryObject()).containsEntry("tenantId", "tenant-a")
                .containsEntry("_id", new Document("$lt", "d"));
        assertThat(query.getValue().getSortObject()).containsEntry("_id", -1);
        assertThat(query.getValue().getLimit()).isEqualTo(3);
        var boundary = ArgumentCaptor.forClass(Query.class);
        verify(mongo).exists(boundary.capture(), eq("candidates"));
        assertThat(boundary.getValue().getQueryObject()).containsEntry("tenantId", "tenant-a")
                .containsEntry("_id", new Document("$gt", "c"));
        assertThat(boundary.getValue().getQueryObject().toString()).contains("Ada", "mergedInto", "erased");
    }

    @Test
    void crossTenantCandidateIsNotReadable() {
        var mongo = mock(MongoTemplate.class);
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        assertThatThrownBy(() -> service.view("tenant-a", "candidate-in-b"))
                .isInstanceOf(ResponseStatusException.class);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(query.capture(), eq(Document.class), eq("candidates"));
        assertThat(query.getValue().getQueryObject())
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("_id", "candidate-in-b");
        verifyNoMoreInteractions(mongo);
    }

    @Test
    void crossTenantSuggestionCannotCreateMergeOperation() {
        var mongo = mock(MongoTemplate.class);
        var service = new CandidateService(mongo, mock(com.openhire.platform.audit.AuditRecorder.class));
        assertThatThrownBy(() -> service.merge("tenant-a", "actor", "suggestion-in-b", new Document()))
                .isInstanceOf(ResponseStatusException.class);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(query.capture(), eq(Document.class), eq("merge_suggestions"));
        assertThat(query.getValue().getQueryObject()).containsEntry("tenantId", "tenant-a");
        verifyNoMoreInteractions(mongo);
    }
}
