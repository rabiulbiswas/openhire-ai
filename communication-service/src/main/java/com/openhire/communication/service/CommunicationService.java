package com.openhire.communication.service;

import com.openhire.communication.domain.CandidatePreferences;
import com.openhire.communication.domain.CommunicationTypes.Audience;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.DeliveryChannel;
import com.openhire.communication.domain.CommunicationTypes.Direction;
import com.openhire.communication.domain.CommunicationTypes.MessageStatus;
import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import com.openhire.communication.domain.CommunicationTypes.Recipient;
import com.openhire.communication.domain.Conversation;
import com.openhire.communication.domain.DefaultTemplates;
import com.openhire.communication.domain.DigestItem;
import com.openhire.communication.domain.Message;
import com.openhire.communication.domain.MessageTemplate;
import com.openhire.communication.domain.NotificationPreferences;
import com.openhire.communication.domain.StaffNotification;
import com.openhire.communication.domain.TriggerRules;
import com.openhire.communication.dto.CommunicationViews.CandidatePreferencesView;
import com.openhire.communication.dto.CommunicationViews.ChatMessageView;
import com.openhire.communication.dto.CommunicationViews.ConversationSummaryView;
import com.openhire.communication.dto.CommunicationViews.ConversationView;
import com.openhire.communication.dto.CommunicationViews.LinkView;
import com.openhire.communication.dto.CommunicationViews.NotificationView;
import com.openhire.communication.dto.CommunicationViews.Page;
import com.openhire.communication.dto.CommunicationViews.PortalThreadView;
import com.openhire.communication.dto.CommunicationViews.PreferencesView;
import com.openhire.communication.dto.CommunicationViews.PreviewView;
import com.openhire.communication.dto.CommunicationViews.QuietHoursView;
import com.openhire.communication.dto.CommunicationViews.TemplateView;
import com.openhire.communication.service.port.CommunicationPorts.CandidatePreferenceStore;
import com.openhire.communication.service.port.CommunicationPorts.Contact;
import com.openhire.communication.service.port.CommunicationPorts.ContactDirectory;
import com.openhire.communication.service.port.CommunicationPorts.ConversationStore;
import com.openhire.communication.service.port.CommunicationPorts.DigestStore;
import com.openhire.communication.service.port.CommunicationPorts.DraftRequest;
import com.openhire.communication.service.port.CommunicationPorts.DraftTurn;
import com.openhire.communication.service.port.CommunicationPorts.DraftingUnavailableException;
import com.openhire.communication.service.port.CommunicationPorts.MessageStore;
import com.openhire.communication.service.port.CommunicationPorts.NotificationStore;
import com.openhire.communication.service.port.CommunicationPorts.PreferenceStore;
import com.openhire.communication.service.port.CommunicationPorts.ReplyDrafter;
import com.openhire.communication.service.port.CommunicationPorts.TemplateStore;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CommunicationService {
    public record StaffViewer(String subject, String email, String name, Set<String> roles) {}

    public record Settings(String companyName, String defaultTimezone) {}

    /** The feature exists in the contract but its provider is not reachable right now. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    private static final int DRAFT_THREAD_LIMIT = 10;
    private static final String DRAFT_UNAVAILABLE = "AI drafting is unavailable right now. Write the reply yourself.";

    private final MessageStore messages;
    private final ConversationStore conversations;
    private final TemplateStore templates;
    private final NotificationStore notifications;
    private final PreferenceStore preferences;
    private final CandidatePreferenceStore candidatePreferences;
    private final DigestStore digests;
    private final ContactDirectory contacts;
    private final ReplyDrafter drafter;
    private final Settings settings;
    private final Clock clock;
    private final AuditRecorder audit;

    public CommunicationService(
            MessageStore messages,
            ConversationStore conversations,
            TemplateStore templates,
            NotificationStore notifications,
            PreferenceStore preferences,
            CandidatePreferenceStore candidatePreferences,
            DigestStore digests,
            ContactDirectory contacts,
            ReplyDrafter drafter,
            Settings settings,
            Clock clock,
            AuditRecorder audit) {
        this.messages = messages;
        this.conversations = conversations;
        this.templates = templates;
        this.notifications = notifications;
        this.preferences = preferences;
        this.candidatePreferences = candidatePreferences;
        this.digests = digests;
        this.contacts = contacts;
        this.drafter = drafter;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    // ---- Lifecycle events -------------------------------------------------------------------------

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the service can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    /** Applies trigger rules to one lifecycle event. Idempotent: every output carries a dedupe key from the event id. */
    public void handleEvent(
            String eventId, String eventType, String tenantId, String applicationId, Map<String, Object> payload) {
        if (!TriggerRules.handles(eventType) || tenantId == null || applicationId == null) return;
        var contact = contacts.contact(tenantId, applicationId);
        // A late event for an erased candidate must not start a new conversation.
        if (tombstones != null && tombstones.isErased(tenantId, contact.candidateId())) return;
        var plan = TriggerRules.plan(eventType, payload, contact.candidateName(), contact.jobTitle());
        if (plan.candidateMessage() != null) {
            var request = plan.candidateMessage();
            sendTemplateToCandidate(
                    tenantId,
                    contact,
                    request.templateKey(),
                    request.variables(),
                    null,
                    eventId + ":" + request.templateKey(),
                    true);
        }
        for (var alert : plan.alerts())
            alertStaff(
                    tenantId,
                    alert.recipient(),
                    alert.type(),
                    alert.eventKey(),
                    alert.title(),
                    alert.body(),
                    alert.route(),
                    alert.params(),
                    eventId);
    }

    /** Approval events carry no application, so they only notify staff. Idempotent through the event id. */
    public void handleApprovalEvent(String eventId, String eventType, String tenantId, Map<String, Object> payload) {
        if (tenantId == null) return;
        for (var alert : TriggerRules.approvalAlerts(eventType, payload))
            alertStaff(
                    tenantId,
                    alert.recipient(),
                    alert.type(),
                    alert.eventKey(),
                    alert.title(),
                    alert.body(),
                    alert.route(),
                    alert.params(),
                    eventId);
    }

    public void applyDelivery(String messageId, String status, String reason) {
        MessageStatus next;
        try {
            next = MessageStatus.valueOf(status);
        } catch (RuntimeException unknown) {
            return;
        }
        messages.find(messageId).ifPresent(message -> {
            if (message.applyDelivery(next, reason, clock.instant())) messages.updateStatus(message);
        });
    }

    // ---- Conversations (staff) ----------------------------------------------------------------------

    public Page<ConversationSummaryView> conversations(String tenantId) {
        return Page.of(conversations.recent(tenantId, 100).stream()
                .filter(conversation -> conversation.lastMessageAt() != null)
                .map(conversation -> new ConversationSummaryView(
                        conversation.id(),
                        conversation.candidateId(),
                        conversation.candidateName(),
                        conversation.channel().name(),
                        conversation.lastMessage(),
                        conversation.lastMessageAt().toString(),
                        conversation.unread()))
                .toList());
    }

    public ConversationView startConversation(String tenantId, String applicationId) {
        var contact = contacts.contact(tenantId, applicationId);
        var conversation = conversations
                .find(tenantId, Conversation.idFor(tenantId, contact.candidateId()))
                .orElseGet(() -> conversations.save(Conversation.start(
                        tenantId,
                        contact.candidateId(),
                        contact.candidateName(),
                        applicationId,
                        preferredChannel(tenantId, contact))));
        return conversationView(conversation);
    }

    public ConversationView conversation(String tenantId, String conversationId) {
        var conversation = requireConversation(tenantId, conversationId);
        if (conversation.unread()) {
            conversation.markRead();
            conversations.save(conversation);
        }
        return conversationView(conversation);
    }

    public ChatMessageView send(
            String tenantId, String conversationId, String text, Channel channel, StaffViewer sender) {
        var conversation = requireConversation(tenantId, conversationId);
        var contact = contacts.contact(tenantId, conversation.applicationId());
        var chosen = channel == null ? preferredChannel(tenantId, contact) : channel;
        if (chosen != Channel.EMAIL && blank(contact.phone()))
            throw new IllegalArgumentException("This candidate has no phone number; send by email instead");
        var subject = "Message from the " + settings.companyName() + " hiring team";
        var message = Message.outbound(
                tenantId,
                conversation.id(),
                Audience.CANDIDATE,
                chosen,
                new Recipient(contact.candidateName(), contact.email(), contact.phone()),
                subject,
                text,
                null,
                null,
                sender.subject(),
                null,
                false,
                candidateQuietHours(tenantId, contact.candidateId()),
                clock.instant());
        if (!messages.insert(message)) throw new IllegalStateException("The message was not saved; send it again");
        conversation.recordOutbound(message.body(), message.createdAt(), sender.subject(), chosen);
        conversations.save(conversation);
        audit.record(AuditRecord.of(tenantId, "STAFF_MESSAGE_SENT")
                .user(sender.subject(), sender.name())
                .resource("Conversation", conversation.id())
                .about(contact.candidateId(), conversation.applicationId())
                .detail("channel", chosen.name())
                .detail("messageId", message.id())
                .build());
        return chatView(message, true);
    }

    /** A suggestion for the reply box. It is returned to the recruiter and never sent automatically. */
    public String aiDraft(String tenantId, String conversationId) {
        var conversation = requireConversation(tenantId, conversationId);
        var contact = contacts.contact(tenantId, conversation.applicationId());
        var thread = messages.conversation(tenantId, conversation.id(), 200).stream()
                .filter(message -> !blank(message.body()))
                .map(message ->
                        new DraftTurn(message.direction() == Direction.OUTBOUND ? "me" : "them", message.body()))
                .toList();
        thread = thread.subList(Math.max(0, thread.size() - DRAFT_THREAD_LIMIT), thread.size());
        var first = blank(contact.candidateName())
                ? ""
                : contact.candidateName().trim().split("\\s+")[0];
        String text;
        try {
            text = drafter.draftReply(
                    tenantId,
                    new DraftRequest(
                            first,
                            blank(contact.jobTitle()) ? null : contact.jobTitle(),
                            thread,
                            conversation.channel().name()));
        } catch (DraftingUnavailableException unavailable) {
            throw new Unavailable(DRAFT_UNAVAILABLE);
        }
        if (blank(text)) throw new Unavailable(DRAFT_UNAVAILABLE);
        return text.trim();
    }

    public record TemplateSend(boolean sent, String reason) {}

    /**
     * Sends a template on a person's click from another service (talent re-engagement). The candidate's channel and
     * quiet hours apply, and a marketing opt-out suppresses the message.
     */
    public TemplateSend sendTemplate(
            String tenantId, String applicationId, String templateKey, Map<String, String> variables, String actor) {
        var contact = contacts.contact(tenantId, applicationId);
        var optedOut = candidatePreferences
                .find(tenantId, contact.candidateId())
                .map(CandidatePreferences::marketingOptOut)
                .orElse(false);
        if (optedOut) return new TemplateSend(false, "MARKETING_OPT_OUT");
        if (blank(contact.email()) && blank(contact.phone())) return new TemplateSend(false, "NO_CONTACT_DETAILS");
        var values = new LinkedHashMap<String, String>(variables == null ? Map.of() : variables);
        var name = blank(contact.candidateName())
                ? ""
                : contact.candidateName().trim().split("\\s+")[0];
        values.putIfAbsent("candidate.firstName", name);
        if (!blank(contact.jobTitle())) values.putIfAbsent("job.title", contact.jobTitle());
        var sent = sendTemplateToCandidate(tenantId, contact, templateKey, values, actor, null, false);
        return new TemplateSend(sent, sent ? null : "NOT_SAVED");
    }

    // ---- Candidate portal --------------------------------------------------------------------------

    public PortalThreadView candidateThread(String tenantId, String applicationId, String receipt) {
        var contact = contacts.verifyReceipt(tenantId, applicationId, receipt);
        var conversation = conversations.find(tenantId, Conversation.idFor(tenantId, contact.candidateId()));
        var thread = conversation
                .map(value -> messages.conversation(tenantId, value.id(), 200).stream()
                        .map(message -> chatView(message, message.direction() == Direction.INBOUND))
                        .toList())
                .orElse(List.of());
        return new PortalThreadView(contact.candidateName(), thread);
    }

    public ChatMessageView candidateReply(String tenantId, String applicationId, String receipt, String text) {
        var contact = contacts.verifyReceipt(tenantId, applicationId, receipt);
        var conversation = conversations
                .find(tenantId, Conversation.idFor(tenantId, contact.candidateId()))
                .orElseGet(() -> Conversation.start(
                        tenantId, contact.candidateId(), contact.candidateName(), applicationId, Channel.EMAIL));
        var message = Message.inbound(
                tenantId, conversation.id(), new Recipient(contact.candidateName(), null, null), text, clock.instant());
        if (!messages.insert(message)) throw new IllegalStateException("The reply was not saved; send it again");
        conversation.recordInbound(message.body(), message.createdAt());
        conversations.save(conversation);
        var recipient = conversation.ownerId() == null ? "role:RECRUITER" : "user:" + conversation.ownerId();
        alertStaff(
                tenantId,
                recipient,
                "CANDIDATE_REPLIED",
                NotificationEventKey.CANDIDATE_REPLIED,
                contact.candidateName() + " replied",
                preview(message.body()),
                "messages",
                Map.of(),
                message.id());
        return chatView(message, true);
    }

    public CandidatePreferencesView candidatePreferences(String tenantId, String applicationId, String receipt) {
        var contact = contacts.verifyReceipt(tenantId, applicationId, receipt);
        return candidatePreferencesView(candidatePreferences
                .find(tenantId, contact.candidateId())
                .orElseGet(() -> CandidatePreferences.defaults(tenantId, contact.candidateId())));
    }

    public CandidatePreferencesView updateCandidatePreferences(
            String tenantId, String applicationId, String receipt, Channel channel, QuietHours quiet, boolean optOut) {
        var contact = contacts.verifyReceipt(tenantId, applicationId, receipt);
        if (channel != null && channel != Channel.EMAIL && blank(contact.phone()))
            throw new IllegalArgumentException("Add a phone number to your application to use "
                    + channel.name().toLowerCase());
        var value = candidatePreferences
                .find(tenantId, contact.candidateId())
                .orElseGet(() -> CandidatePreferences.defaults(tenantId, contact.candidateId()));
        value.update(channel, quiet, optOut, clock.instant());
        return candidatePreferencesView(candidatePreferences.save(value));
    }

    // ---- Templates ---------------------------------------------------------------------------------

    public List<TemplateView> templates(String tenantId) {
        seedTemplates(tenantId);
        return templates.all(tenantId).stream()
                .map(CommunicationService::templateView)
                .toList();
    }

    public TemplateView updateTemplate(
            String tenantId, String key, List<Channel> channels, String subject, String body, StaffViewer by) {
        seedTemplates(tenantId);
        var template = templates
                .find(tenantId, key)
                .orElseThrow(() -> new ResourceNotFoundException("Template " + key + " was not found"));
        template.revise(channels, subject, body, by.name() == null ? by.subject() : by.name(), clock.instant());
        var saved = templates.save(template);
        audit.record(AuditRecord.of(tenantId, "MESSAGE_TEMPLATE_CHANGED")
                .user(by.subject(), by.name())
                .resource("MessageTemplate", key)
                .detail("version", saved.version())
                .build());
        return templateView(saved);
    }

    public PreviewView preview(String tenantId, String key, Map<String, String> sample) {
        seedTemplates(tenantId);
        var template = templates
                .find(tenantId, key)
                .orElseThrow(() -> new ResourceNotFoundException("Template " + key + " was not found"));
        var values = new LinkedHashMap<String, String>();
        values.put("candidate.firstName", "Karthik");
        values.put("candidate.name", "Karthik Rao");
        values.put("job.title", "Senior Java Backend Engineer");
        values.put("round.name", "Technical");
        values.put("round.start", "Tue 13 Oct 2026, 11:00");
        values.put("round.timezone", settings.defaultTimezone());
        values.put("meeting.url", "https://meet.example.com/abc");
        values.put("booking.url", "https://careers.example.com/schedule");
        values.put("company.name", settings.companyName());
        values.put("recruiter.name", "Arjun Mehta");
        if (sample != null) values.putAll(sample);
        var rendered = template.render(values);
        return new PreviewView(rendered.subject(), rendered.body());
    }

    // ---- Staff notifications and preferences -------------------------------------------------------

    public Page<NotificationView> notifications(String tenantId, StaffViewer viewer, String filter, int limit) {
        var keys = StaffNotification.recipientKeys(viewer.subject(), viewer.email(), viewer.roles());
        return Page.of(notifications.list(tenantId, keys, filter, Math.min(Math.max(limit, 1), 100)).stream()
                .map(value -> new NotificationView(
                        value.id(),
                        value.type(),
                        value.title(),
                        value.body(),
                        new LinkView(value.route(), value.params()),
                        value.createdAt().toString(),
                        value.readAt() == null ? null : value.readAt().toString(),
                        value.mention()))
                .toList());
    }

    public long unreadCount(String tenantId, StaffViewer viewer) {
        return notifications.unread(
                tenantId, StaffNotification.recipientKeys(viewer.subject(), viewer.email(), viewer.roles()));
    }

    public void markRead(String tenantId, String id, StaffViewer viewer) {
        if (!notifications.markRead(
                tenantId, id, StaffNotification.recipientKeys(viewer.subject(), viewer.email(), viewer.roles())))
            throw new ResourceNotFoundException("Notification " + id + " was not found");
    }

    public void markAllRead(String tenantId, StaffViewer viewer) {
        notifications.markAllRead(
                tenantId, StaffNotification.recipientKeys(viewer.subject(), viewer.email(), viewer.roles()));
    }

    public PreferencesView preferences(String tenantId, StaffViewer viewer) {
        return preferencesView(currentPreferences(tenantId, viewer));
    }

    public PreferencesView updatePreferences(
            String tenantId,
            StaffViewer viewer,
            Map<NotificationEventKey, Map<DeliveryChannel, Boolean>> matrix,
            QuietHours quiet,
            NotificationPreferences.Digest digest) {
        var value = currentPreferences(tenantId, viewer);
        value.update(matrix, quiet, digest, clock.instant());
        return preferencesView(preferences.save(value));
    }

    /** Sends one digest email per staff member whose hourly or daily digest is due. */
    public int sendDueDigests() {
        int sent = 0;
        var now = clock.instant();
        for (var value : preferences.withDigest()) {
            if (!value.digestDue(now) || blank(value.email())) continue;
            var items = digests.take(value.tenantId(), value.subject());
            value.markDigestSent(now);
            preferences.save(value);
            if (items.isEmpty()) continue;
            var body = new StringBuilder("Here is what happened since your last digest:\n\n");
            items.forEach(item -> body.append("- ")
                    .append(item.title())
                    .append(": ")
                    .append(item.body())
                    .append('\n'));
            messages.insert(Message.outbound(
                    value.tenantId(),
                    null,
                    Audience.STAFF,
                    Channel.EMAIL,
                    new Recipient(null, value.email(), null),
                    items.size() + (items.size() == 1 ? " update" : " updates") + " from OpenHire",
                    body.toString(),
                    null,
                    null,
                    null,
                    "digest:" + value.id() + ":" + now.getEpochSecond(),
                    false,
                    value.effectiveQuietHours(),
                    now));
            sent++;
        }
        return sent;
    }

    // ---- Internals ---------------------------------------------------------------------------------

    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.platform.security.TenantStaffDirectory staff;

    private void alertStaff(
            String tenantId,
            String recipient,
            String type,
            NotificationEventKey eventKey,
            String title,
            String body,
            String route,
            Map<String, String> params,
            String sourceId) {
        var prefs = preferencesFor(tenantId, recipient);
        if (prefs.allows(eventKey, DeliveryChannel.IN_APP))
            notifications.insert(StaffNotification.create(
                    tenantId,
                    recipient,
                    type,
                    eventKey,
                    title,
                    body,
                    route,
                    params,
                    "ASSIGNED".equals(type),
                    sourceId + ":" + recipient,
                    clock.instant()));
        var address = recipient.startsWith("email:") ? recipient.substring(6) : prefs.email();
        if (recipient.startsWith("user:")) {
            var member = staff.members(tenantId).stream()
                    .filter(m -> m.id().equals(recipient.substring(5)))
                    .findFirst();
            if (member.isEmpty()) return;
            address = member.get().email();
        }
        var workflowEmail = eventKey == NotificationEventKey.APPROVAL_REQUESTED
                || eventKey == NotificationEventKey.OFFER_ACCEPTED_DECLINED;
        if ((!workflowEmail && !prefs.allows(eventKey, DeliveryChannel.EMAIL))
                || blank(address)
                || recipient.startsWith("role:")) return;
        if (!workflowEmail
                && prefs.subject() != null
                && prefs.emailDigest() != NotificationPreferences.Digest.IMMEDIATE) {
            digests.add(DigestItem.create(tenantId, prefs.subject(), title, body, clock.instant()));
            return;
        }
        messages.insert(Message.outbound(
                tenantId,
                null,
                Audience.STAFF,
                Channel.EMAIL,
                new Recipient(null, address, null),
                title,
                body + "\n\nOpen OpenHire to see the details.",
                null,
                null,
                null,
                "staff:" + sourceId + ":" + recipient,
                workflowEmail,
                workflowEmail
                        ? com.openhire.communication.domain.CommunicationTypes.QuietHours.off()
                        : prefs.effectiveQuietHours(),
                clock.instant()));
    }

    private boolean sendTemplateToCandidate(
            String tenantId,
            Contact contact,
            String key,
            Map<String, String> variables,
            String actor,
            String dedupeKey,
            boolean transactional) {
        seedTemplates(tenantId);
        var template = templates
                .find(tenantId, key)
                .orElseThrow(() -> new ResourceNotFoundException("Template " + key + " was not found"));
        var values = new LinkedHashMap<>(variables);
        values.putIfAbsent("company.name", settings.companyName());
        values.putIfAbsent("recruiter.name", "The " + settings.companyName() + " hiring team");
        var rendered = template.render(values);
        var channel = preferredChannel(tenantId, contact);
        if (!template.channels().contains(channel)) channel = Channel.EMAIL;
        var conversation = conversations
                .find(tenantId, Conversation.idFor(tenantId, contact.candidateId()))
                .orElseGet(() -> Conversation.start(
                        tenantId,
                        contact.candidateId(),
                        contact.candidateName(),
                        contact.applicationId(),
                        Channel.EMAIL));
        var message = Message.outbound(
                tenantId,
                conversation.id(),
                Audience.CANDIDATE,
                channel,
                new Recipient(contact.candidateName(), contact.email(), contact.phone()),
                rendered.subject(),
                rendered.body(),
                template.key(),
                template.version(),
                actor,
                dedupeKey,
                transactional,
                candidateQuietHours(tenantId, contact.candidateId()),
                clock.instant());
        if (!messages.insert(message)) return false;
        conversation.recordOutbound(message.body(), message.createdAt(), actor, channel);
        conversations.save(conversation);
        return true;
    }

    private void seedTemplates(String tenantId) {
        // The last seed marks a complete set; templates added in later phases reach existing tenants too.
        if (templates.find(tenantId, DefaultTemplates.ALL.getLast().key()).isPresent()) return;
        for (var seed : DefaultTemplates.ALL)
            templates.insertIfAbsent(MessageTemplate.create(
                    tenantId,
                    seed.key(),
                    seed.name(),
                    seed.channels(),
                    seed.subject(),
                    seed.body(),
                    "system",
                    clock.instant()));
    }

    private NotificationPreferences preferencesFor(String tenantId, String recipient) {
        if (recipient.startsWith("user:"))
            return preferences
                    .find(tenantId, recipient.substring(5))
                    .orElseGet(
                            () -> NotificationPreferences.defaults(tenantId, null, null, settings.defaultTimezone()));
        if (recipient.startsWith("email:"))
            return preferences
                    .findByEmail(tenantId, recipient.substring(6))
                    .orElseGet(() -> NotificationPreferences.defaults(
                            tenantId, null, recipient.substring(6), settings.defaultTimezone()));
        return NotificationPreferences.defaults(tenantId, null, null, settings.defaultTimezone());
    }

    private NotificationPreferences currentPreferences(String tenantId, StaffViewer viewer) {
        var value = preferences
                .find(tenantId, viewer.subject())
                .orElseGet(() -> NotificationPreferences.defaults(
                        tenantId, viewer.subject(), viewer.email(), settings.defaultTimezone()));
        if (value.refreshContact(viewer.email()) || value.matrix() == null) preferences.save(value);
        return value;
    }

    private Channel preferredChannel(String tenantId, Contact contact) {
        return candidatePreferences
                .find(tenantId, contact.candidateId())
                .map(value -> value.channelFor(!blank(contact.phone())))
                .orElse(Channel.EMAIL);
    }

    private QuietHours candidateQuietHours(String tenantId, String candidateId) {
        return candidatePreferences
                .find(tenantId, candidateId)
                .map(CandidatePreferences::quietHours)
                .orElse(QuietHours.off());
    }

    private Conversation requireConversation(String tenantId, String conversationId) {
        return conversations
                .find(tenantId, conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation " + conversationId + " was not found"));
    }

    private ConversationView conversationView(Conversation conversation) {
        var thread = messages.conversation(conversation.tenantId(), conversation.id(), 200).stream()
                .map(message -> chatView(message, message.direction() == Direction.OUTBOUND))
                .toList();
        return new ConversationView(
                conversation.id(),
                conversation.candidateId(),
                conversation.candidateName(),
                conversation.applicationId(),
                conversation.channel().name(),
                thread,
                null);
    }

    private static ChatMessageView chatView(Message message, boolean mine) {
        return new ChatMessageView(
                message.id(),
                mine ? "me" : "them",
                message.body(),
                message.createdAt().toString(),
                message.status().name(),
                message.statusReason(),
                message.channel().name());
    }

    private static TemplateView templateView(MessageTemplate template) {
        return new TemplateView(
                template.key(),
                template.key(),
                template.name(),
                template.channels().stream().map(Channel::name).toList(),
                template.subject(),
                template.body(),
                template.version(),
                template.updatedAt().toString(),
                MessageTemplate.VARIABLES);
    }

    private static PreferencesView preferencesView(NotificationPreferences value) {
        var matrix = new LinkedHashMap<String, Map<String, Boolean>>();
        for (var key : NotificationEventKey.values()) {
            var row = new LinkedHashMap<String, Boolean>();
            for (var channel : DeliveryChannel.values()) row.put(channel.name(), value.allows(key, channel));
            matrix.put(key.name(), row);
        }
        var quiet = value.quietHours();
        return new PreferencesView(
                matrix,
                new QuietHoursView(quiet.enabled(), quiet.from(), quiet.to()),
                value.emailDigest().name());
    }

    private static CandidatePreferencesView candidatePreferencesView(CandidatePreferences value) {
        var quiet = value.quietHours();
        return new CandidatePreferencesView(
                value.preferredChannel().name(),
                new QuietHoursView(quiet.enabled(), quiet.from(), quiet.to()),
                quiet.timezone(),
                value.marketingOptOut());
    }

    private static String preview(String text) {
        return text.length() <= 140 ? text : text.substring(0, 137) + "...";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
