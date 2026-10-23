package com.openhire.candidateservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

class CandidateMergeAuditTest {
    private MongoTemplate pendingMerge() {
        var mongo = mock(MongoTemplate.class);
        var op = new Document("_id", "op1")
                .append("tenantId", "t1")
                .append("actor", "user-7")
                .append("a", "cand-a")
                .append("b", "cand-b")
                .append("keep", new Document())
                .append("status", "PENDING");
        when(mongo.find(any(Query.class), eq(Document.class), eq("candidate_merges")))
                .thenReturn(List.of(op), List.of());
        when(mongo.findAndModify(any(), any(), any(), eq(Document.class), any()))
                .thenReturn(new Document());
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("candidates")))
                .thenReturn(new Document("_id", "cand-a"), new Document("_id", "cand-b"));
        return mongo;
    }

    @Test
    void completedMergeIsAuditedWithIdsOnly() {
        var audit = mock(AuditRecorder.class);
        new CandidateService(pendingMerge(), audit).recoverMerges();
        var record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        var r = record.getValue();
        assertThat(r.action()).isEqualTo("CANDIDATE_MERGED");
        assertThat(r.tenantId()).isEqualTo("t1");
        assertThat(r.actorType()).isEqualTo(AuditRecord.ActorType.USER);
        assertThat(r.actorId()).isEqualTo("user-7");
        assertThat(r.resourceType()).isEqualTo("Candidate");
        assertThat(r.resourceId()).isEqualTo("cand-a");
        assertThat(r.details()).containsOnlyKeys("survivorId", "sourceId");
    }

    @Test
    void recorderFailureDoesNotBreakTheMerge() {
        var mongo = pendingMerge();
        var broken = new AuditRecorder(
                new AuditRecorder.Outbox() {
                    public void add(String eventId, String tenantId, String json) {
                        throw new IllegalStateException("outbox down");
                    }

                    public List<Pending> pending(int limit) {
                        return List.of();
                    }

                    public void remove(String eventId) {}
                },
                (key, json) -> {},
                "candidate-service",
                true);
        assertThatCode(() -> new CandidateService(mongo, broken).recoverMerges())
                .doesNotThrowAnyException();
        verify(mongo, atLeastOnce()).updateFirst(any(Query.class), any(UpdateDefinition.class), eq("candidate_merges"));
    }
}
