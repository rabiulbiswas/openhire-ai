package com.openhire.compliance.domain;

import java.util.List;
import java.util.Optional;

/**
 * The tamper-evident chain over a tenant's audit records.
 *
 * <p>Algorithm: {@code hash = SHA-256(prevHash + canonicalJson(record without hash))}, hex encoded, where the record is
 * {@link AuditEntry#hashable()} and the canonical form is defined by {@link CanonicalJson}. {@code seq} starts at 1 per
 * tenant and has no gaps; the first record's {@code prevHash} is {@link #GENESIS} (64 zeros). Changing, removing,
 * inserting or reordering any record breaks its own hash or the link to the next one.
 */
public final class AuditChain {
    public static final String GENESIS = "0".repeat(64);

    private AuditChain() {}

    public static String hash(String prevHash, AuditEntry entry) {
        return CanonicalJson.sha256(prevHash + CanonicalJson.write(entry.hashable()));
    }

    /** Seals an entry built with the right seq and prevHash. */
    public static AuditEntry seal(AuditEntry unsealed) {
        return new AuditEntry(
                unsealed.id(),
                unsealed.tenantId(),
                unsealed.seq(),
                unsealed.eventId(),
                unsealed.action(),
                unsealed.actor(),
                unsealed.resource(),
                unsealed.candidateId(),
                unsealed.applicationId(),
                unsealed.details(),
                unsealed.source(),
                unsealed.at(),
                unsealed.prevHash(),
                hash(unsealed.prevHash(), unsealed));
    }

    public record Result(boolean valid, long checked, Long brokenAtSeq) {}

    /** Incremental verifier: feed records in seq order, in as many batches as needed. */
    public static final class Verifier {
        private long expectedSeq = 1;
        private String previous = GENESIS;
        private long checked;
        private Long broken;

        public boolean feed(List<AuditEntry> batch) {
            for (var entry : batch) {
                if (entry.seq() != expectedSeq
                        || !previous.equals(entry.prevHash())
                        || !hash(entry.prevHash(), entry).equals(entry.hash())) {
                    broken = expectedSeq;
                    return false;
                }
                checked++;
                expectedSeq++;
                previous = entry.hash();
            }
            return true;
        }

        public long lastSeq() {
            return expectedSeq - 1;
        }

        public Result result() {
            return new Result(broken == null, checked, broken);
        }
    }

    public static Optional<Long> firstBroken(List<AuditEntry> entries) {
        var verifier = new Verifier();
        verifier.feed(entries);
        return Optional.ofNullable(verifier.result().brokenAtSeq());
    }
}
