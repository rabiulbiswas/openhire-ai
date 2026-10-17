package com.openhire.offer.application;

import java.math.BigDecimal;
import java.util.List;

public final class OfferViews {
    private OfferViews() {}

    public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {
        public static <T> Page<T> of(List<T> data) {
            return new Page<>(data, null, false);
        }
    }

    public record OfferSummary(
            String id,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String status,
            String detail,
            int version) {}

    public record StepView(
            String id, String approver, String role, String status, String at, String decidedBy, String comment) {}

    public record TermsView(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String joiningDateNote,
            String reportsTo,
            String location,
            String expiryDate,
            boolean withinBand,
            String designation) {}

    public record BandView(String currency, BigDecimal min, BigDecimal max) {}

    public record VersionView(int versionNo, String createdAt, String createdBy, String sha256, boolean sent) {}

    public record OfferDetail(
            String id,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String status,
            String detail,
            int version,
            List<StepView> approvalSteps,
            String approvalNote,
            String templateName,
            String coverNote,
            TermsView terms,
            BandView band,
            String expiresAt,
            String sentAt,
            String respondedAt,
            String candidateNote,
            List<VersionView> versions) {}

    public record PublicTerms(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String location,
            String designation,
            String reportsTo,
            String expiryDate) {}

    public record PublicOfferView(
            String jobTitle,
            String companyName,
            String candidateName,
            String status,
            PublicTerms terms,
            String coverNote,
            String letterText,
            String expiresAt,
            String respondedAt,
            boolean canRespond,
            boolean revising) {}

    public record ReceiptOfferView(String status, String offerUrl) {}

    public record TemplateView(
            String key,
            String name,
            String country,
            String level,
            int version,
            String body,
            List<String> clauseKeys,
            String updatedAt) {}

    public record ClauseView(String key, String title, String body, int version, String updatedAt) {}
}
