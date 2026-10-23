package com.openhire.communication.service.port;

import com.openhire.communication.domain.CandidatePreferences;
import com.openhire.communication.domain.Conversation;
import com.openhire.communication.domain.DigestItem;
import com.openhire.communication.domain.Message;
import com.openhire.communication.domain.MessageTemplate;
import com.openhire.communication.domain.NotificationPreferences;
import com.openhire.communication.domain.StaffNotification;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class CommunicationPorts {
    private CommunicationPorts() {}

    public interface MessageStore {
        /** Returns false when a message with the same dedupe key already exists (event redelivery). */
        boolean insert(Message message);

        Optional<Message> find(String messageId);

        List<Message> conversation(String tenantId, String conversationId, int limit);

        /** Persists only the delivery status fields, never the pending events. */
        void updateStatus(Message message);
    }

    public interface ConversationStore {
        Optional<Conversation> find(String tenantId, String id);

        Conversation save(Conversation conversation);

        List<Conversation> recent(String tenantId, int limit);
    }

    public interface TemplateStore {
        Optional<MessageTemplate> find(String tenantId, String key);

        List<MessageTemplate> all(String tenantId);

        MessageTemplate save(MessageTemplate template);

        /** Inserts the template unless the tenant already has one with that key. */
        void insertIfAbsent(MessageTemplate template);
    }

    public interface NotificationStore {
        boolean insert(StaffNotification notification);

        List<StaffNotification> list(String tenantId, Collection<String> recipients, String filter, int limit);

        long unread(String tenantId, Collection<String> recipients);

        boolean markRead(String tenantId, String id, Collection<String> recipients);

        void markAllRead(String tenantId, Collection<String> recipients);
    }

    public interface PreferenceStore {
        Optional<NotificationPreferences> find(String tenantId, String subject);

        Optional<NotificationPreferences> findByEmail(String tenantId, String email);

        NotificationPreferences save(NotificationPreferences preferences);

        List<NotificationPreferences> withDigest();
    }

    public interface CandidatePreferenceStore {
        Optional<CandidatePreferences> find(String tenantId, String candidateId);

        CandidatePreferences save(CandidatePreferences preferences);
    }

    public interface DigestStore {
        void add(DigestItem item);

        List<DigestItem> take(String tenantId, String subject);
    }

    public record Contact(
            String applicationId,
            String candidateId,
            String candidateName,
            String email,
            String phone,
            String jobTitle) {}

    public interface ContactDirectory {
        Contact contact(String tenantId, String applicationId);

        /** Returns the candidate behind a valid application receipt, or throws. */
        Contact verifyReceipt(String tenantId, String applicationId, String receipt);
    }

    /** What the drafting model may see: the candidate's first name, the role and a short thread, never contact data. */
    public record DraftTurn(String from, String text) {}

    public record DraftRequest(String candidateFirstName, String jobTitle, List<DraftTurn> thread, String channel) {}

    /** Thrown when the model server is down, over budget or too slow; the caller keeps the manual flow. */
    public static final class DraftingUnavailableException extends RuntimeException {
        public DraftingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface ReplyDrafter {
        String draftReply(String tenantId, DraftRequest request);
    }
}
