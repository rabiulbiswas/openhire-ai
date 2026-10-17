package com.openhire.offer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class OfferTypes {
    private OfferTypes() {}

    public enum Status {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        SENT,
        VIEWED,
        NEGOTIATING,
        ACCEPTED,
        DECLINED,
        EXPIRED,
        RESCINDED;

        /** The candidate can still respond to the offer in these states. */
        public boolean open() {
            return this == SENT || this == VIEWED || this == NEGOTIATING;
        }

        public boolean closed() {
            return this == DECLINED || this == EXPIRED || this == RESCINDED;
        }
    }

    public enum StepStatus {
        DONE,
        CURRENT,
        PENDING
    }

    /** Offered terms. Money is in the unit the job's salary band uses. */
    public record Terms(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String joiningDateNote,
            String reportsTo,
            String location,
            String expiryDate,
            String designation) {
        public boolean sameCompensation(Terms other) {
            return base.compareTo(other.base) == 0
                    && variable.compareTo(other.variable) == 0
                    && bonus.compareTo(other.bonus) == 0
                    && currency.equals(other.currency);
        }
    }

    public record Band(String currency, BigDecimal min, BigDecimal max) {
        public BigDecimal midpoint() {
            return min.add(max).divide(BigDecimal.valueOf(2));
        }
    }

    public record ApprovalStep(
            String id,
            int seq,
            String role,
            String label,
            StepStatus status,
            String decidedBy,
            String decidedName,
            Instant decidedAt,
            String comment) {
        public ApprovalStep with(StepStatus next) {
            return new ApprovalStep(id, seq, role, label, next, decidedBy, decidedName, decidedAt, comment);
        }

        public ApprovalStep decided(String by, String name, Instant at, String note) {
            return new ApprovalStep(id, seq, role, label, StepStatus.DONE, by, name, at, note);
        }
    }

    /** One immutable revision of the offer. A version is frozen once it is submitted or sent. */
    public record OfferVersion(
            int versionNo,
            String templateKey,
            int templateVersion,
            Terms terms,
            String coverNote,
            String letterText,
            String sha256,
            String createdBy,
            Instant createdAt,
            boolean frozen,
            boolean sent) {
        public OfferVersion freeze() {
            return new OfferVersion(
                    versionNo,
                    templateKey,
                    templateVersion,
                    terms,
                    coverNote,
                    letterText,
                    sha256,
                    createdBy,
                    createdAt,
                    true,
                    sent);
        }

        public OfferVersion markSent() {
            return new OfferVersion(
                    versionNo,
                    templateKey,
                    templateVersion,
                    terms,
                    coverNote,
                    letterText,
                    sha256,
                    createdBy,
                    createdAt,
                    true,
                    true);
        }
    }

    public record PendingEvent(
            String eventId,
            String producer,
            String eventType,
            int eventVersion,
            String occurredAt,
            String tenantId,
            String aggregateType,
            String aggregateId,
            String correlationId,
            Map<String, Object> payload) {}

    /** The signed-in staff member. Roles are Keycloak realm roles without the ROLE_ prefix. */
    public record Viewer(String subject, String email, String name, Set<String> roles) {
        private boolean any(String... wanted) {
            for (var role : wanted) if (roles.contains(role)) return true;
            return false;
        }

        /** What to show other people: the person's name, falling back to their email and then their id. */
        public String displayName() {
            if (name != null && !name.isBlank()) return name;
            return email != null && !email.isBlank() ? email : subject;
        }

        public boolean canCreate() {
            return any("RECRUITER", "HR_ADMIN", "ORG_ADMIN", "SUPER_ADMIN");
        }

        public boolean canRead() {
            return any("RECRUITER", "HR_ADMIN", "HIRING_MANAGER", "ORG_ADMIN", "SUPER_ADMIN");
        }

        public boolean canRescind() {
            return any("HR_ADMIN", "ORG_ADMIN", "SUPER_ADMIN");
        }

        /** FINANCE steps are taken by org admins; SUPER_ADMIN may take any step. */
        public boolean canApprove(String stepRole) {
            if (roles.contains("SUPER_ADMIN") || roles.contains(stepRole)) return true;
            return "FINANCE".equals(stepRole) && roles.contains("ORG_ADMIN");
        }
    }
}
