package com.openhire.communication.dto;

import java.util.List;
import java.util.Map;

/** Read models for the console and candidate portal; names follow the TypeScript contracts. */
public final class CommunicationViews {
    private CommunicationViews() {}

    public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {
        public static <T> Page<T> of(List<T> data) {
            return new Page<>(data, null, false);
        }
    }

    public record ConversationSummaryView(
            String id,
            String candidateId,
            String candidateName,
            String channel,
            String lastMessage,
            String lastMessageAt,
            boolean unread) {}

    public record ChatMessageView(
            String id, String from, String text, String at, String status, String statusReason, String channel) {}

    public record ConversationView(
            String id,
            String candidateId,
            String candidateName,
            String applicationId,
            String channel,
            List<ChatMessageView> messages,
            String aiDraftReply) {}

    public record PortalThreadView(String candidateName, List<ChatMessageView> messages) {}

    public record TemplateView(
            String id,
            String key,
            String name,
            List<String> channels,
            String subject,
            String body,
            int version,
            String updatedAt,
            List<String> variables) {}

    public record PreviewView(String subject, String body) {}

    public record LinkView(String route, Map<String, String> params) {}

    public record NotificationView(
            String id,
            String type,
            String title,
            String body,
            LinkView link,
            String createdAt,
            String readAt,
            boolean mention) {}

    public record QuietHoursView(boolean enabled, String from, String to) {}

    public record PreferencesView(
            Map<String, Map<String, Boolean>> matrix, QuietHoursView quietHours, String emailDigest) {}

    public record CandidatePreferencesView(
            String preferredChannel, QuietHoursView quietHours, String timezone, boolean marketingOptOut) {}
}
