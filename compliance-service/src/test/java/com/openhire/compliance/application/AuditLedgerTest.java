package com.openhire.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.compliance.application.port.CompliancePorts.AuditFilter;
import com.openhire.compliance.domain.AuditChain;
import com.openhire.compliance.domain.AuditEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AuditLedgerTest {
    private final Rig rig = new Rig();

    private Map<String, Object> event(String tenant, String action) {
        return Rig.auditEvent(tenant, action, "app_1", "cand_1", "2026-10-15T10:00:00Z");
    }

    @Test
    void recordsFormAGaplessChainStartingFromZeros() {
        for (int i = 0; i < 5; i++) rig.ledger.ingest(event("t1", "OFFER_SENT"));
        rig.ledger.ingest(event("t2", "OFFER_SENT"));

        var rows = rig.audit.after("t1", 0, null, null, 100);
        assertThat(rows).extracting(AuditEntry::seq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(rows.getFirst().prevHash()).isEqualTo("0".repeat(64));
        assertThat(rows.get(1).prevHash()).isEqualTo(rows.getFirst().hash());
        assertThat(rig.audit.last("t2").orElseThrow().seq()).isEqualTo(1L);
        assertThat(rig.ledger.verify("t1")).isEqualTo(new AuditChain.Result(true, 5, null));
    }

    @Test
    void theSameEventIsStoredOnce() {
        var event = event("t1", "OFFER_SENT");
        assertThat(rig.ledger.ingest(event)).isTrue();
        assertThat(rig.ledger.ingest(event)).isFalse();
        assertThat(rig.audit.rows).hasSize(1);
    }

    @Test
    void otherEventTypesAreIgnored() {
        var event = event("t1", "OFFER_SENT");
        event.put("eventType", "offer.sent.v1");
        assertThat(rig.ledger.ingest(event)).isFalse();
        assertThat(rig.audit.rows).isEmpty();
    }

    @Test
    void tamperingWithAnyFieldBreaksVerificationAtThatRecord() {
        for (int i = 0; i < 4; i++) rig.ledger.ingest(event("t1", "OFFER_SENT"));
        var original = rig.audit.rows.get(1);
        var forged = new AuditEntry(
                original.id(),
                original.tenantId(),
                original.seq(),
                original.eventId(),
                "OFFER_APPROVED",
                original.actor(),
                original.resource(),
                original.candidateId(),
                original.applicationId(),
                original.details(),
                original.source(),
                original.at(),
                original.prevHash(),
                original.hash());
        rig.audit.replace(1, forged);

        var result = rig.ledger.verify("t1");
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(2L);
    }

    @Test
    void aDeletedRecordIsDetectedAsAGap() {
        for (int i = 0; i < 4; i++) rig.ledger.ingest(event("t1", "OFFER_SENT"));
        rig.audit.rows.remove(2);

        var result = rig.ledger.verify("t1");
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(3L);
    }

    @Test
    void concurrentEventsForOneTenantStillGetAGaplessChain() throws Exception {
        int events = 60;
        var pool = Executors.newFixedThreadPool(8);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < events; i++) tasks.add(() -> rig.ledger.ingest(event("t1", "OFFER_SENT")));
        for (var future : pool.invokeAll(tasks)) assertThat(future.get()).isTrue();
        pool.shutdown();

        var rows = rig.audit.after("t1", 0, null, null, 1000);
        assertThat(rows)
                .extracting(AuditEntry::seq)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, events)
                        .boxed()
                        .toList());
        assertThat(rig.ledger.verify("t1").valid()).isTrue();
    }

    @Test
    void listFiltersPagesNewestFirstAndKeepsTenantsApart() {
        for (int i = 0; i < 5; i++) rig.ledger.ingest(event("t1", i % 2 == 0 ? "OFFER_SENT" : "OFFER_APPROVED"));
        rig.ledger.ingest(event("t2", "OFFER_SENT"));

        var first = rig.ledger.list("t1", AuditFilter.none(), null, 2);
        assertThat(first.data()).extracting(m -> m.get("seq")).containsExactly(5L, 4L);
        assertThat(first.hasMore()).isTrue();
        var second = rig.ledger.list("t1", AuditFilter.none(), first.nextCursor(), 10);
        assertThat(second.data()).extracting(m -> m.get("seq")).containsExactly(3L, 2L, 1L);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();

        var sent = rig.ledger.list(
                "t1", new AuditFilter(null, "OFFER_SENT", null, null, null, null, null, null), null, 10);
        assertThat(sent.data()).hasSize(3);
        assertThat(rig.ledger.list("t2", AuditFilter.none(), null, 10).data()).hasSize(1);
    }

    @Test
    void exportStreamsOldestFirstAndAuditsItself() {
        for (int i = 0; i < 3; i++) rig.ledger.ingest(event("t1", "OFFER_SENT"));
        var seen = new ArrayList<Long>();

        rig.ledger.export("t1", new Actor("u9", "Priya"), null, null, entry -> seen.add(entry.seq()));

        assertThat(seen).containsExactly(1L, 2L, 3L);
        var exports = rig.sink.of("DATA_EXPORT");
        assertThat(exports).hasSize(1);
        assertThat(exports.getFirst().actorId()).isEqualTo("u9");
    }

    @Test
    void hashingIsIndependentOfKeyOrderAndNumberFormat() {
        var a = Map.<String, Object>of("b", 1, "a", List.of(1.0, "x"));
        var b = new java.util.LinkedHashMap<String, Object>();
        b.put("a", List.of(1, "x"));
        b.put("b", 1L);
        assertThat(com.openhire.compliance.domain.CanonicalJson.write(a))
                .isEqualTo(com.openhire.compliance.domain.CanonicalJson.write(b))
                .isEqualTo("{\"a\":[1,\"x\"],\"b\":1}");
    }
}
