package com.openhire.candidateservice;

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
