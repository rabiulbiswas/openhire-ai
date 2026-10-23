package com.openhire.communication.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.communication.AuditProbe;
import com.openhire.communication.domain.CandidatePreferences;
import com.openhire.communication.domain.CommunicationTypes.Audience;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.DeliveryChannel;
import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import com.openhire.communication.domain.Conversation;
import com.openhire.communication.domain.DigestItem;
import com.openhire.communication.domain.Message;
import com.openhire.communication.domain.MessageTemplate;
import com.openhire.communication.domain.NotificationPreferences;
import com.openhire.communication.domain.StaffNotification;
import com.openhire.communication.dto.CommunicationViews;
import com.openhire.communication.service.CommunicationService.StaffViewer;
import com.openhire.communication.service.port.CommunicationPorts.CandidatePreferenceStore;
import com.openhire.communication.service.port.CommunicationPorts.Contact;
import com.openhire.communication.service.port.CommunicationPorts.ContactDirectory;
import com.openhire.communication.service.port.CommunicationPorts.ConversationStore;
import com.openhire.communication.service.port.CommunicationPorts.DigestStore;
import com.openhire.communication.service.port.CommunicationPorts.DraftRequest;
import com.openhire.communication.service.port.CommunicationPorts.DraftingUnavailableException;
import com.openhire.communication.service.port.CommunicationPorts.MessageStore;
import com.openhire.communication.service.port.CommunicationPorts.NotificationStore;
import com.openhire.communication.service.port.CommunicationPorts.PreferenceStore;
import com.openhire.communication.service.port.CommunicationPorts.ReplyDrafter;
import com.openhire.communication.service.port.CommunicationPorts.TemplateStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommunicationServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("UTC"));
    private final Memory memory = new Memory();
    private final StaffViewer recruiter = new StaffViewer("sub-r", "r@x.test", "Riya Recruiter", Set.of("RECRUITER"));
    private final StaffViewer asha = new StaffViewer("sub-a", "asha@x.test", "Asha", Set.of("INTERVIEWER"));
    private final AuditProbe audit = AuditProbe.working();
    private final CommunicationService service = new CommunicationService(
            memory.messageStore,
            memory.conversationStore,
            memory.templateStore,
            memory.notificationStore,
            memory.preferenceStore,
            memory.candidateStore,
            memory.digestStore,
            memory.contacts,
            memory.drafter,
            new CommunicationService.Settings("Northwind", "Asia/Kolkata"),
            clock,
            audit.recorder());

    private Map<String, Object> scheduled() {
        return Map.of(
                "applicationId", "app-1",
                "roundName", "Technical",
                "start", "2026-10-13T05:30:00Z",
                "timezone", "Asia/Kolkata",
                "ownerId", "sub-r",
                "panel", List.of(Map.of("name", "Asha", "email", "asha@x.test")));
    }

    @Test
    void aScheduledRoundEmailsTheCandidateAndNotifiesPanelAndOwnerExactlyOnceUnderRedelivery() {
        service.handleEvent("evt-1", "interview.scheduled.v1", "t1", "app-1", scheduled());
        service.handleEvent("evt-1", "interview.scheduled.v1", "t1", "app-1", scheduled());

        var candidateMessages = memory.messages.stream()
                .filter(m -> m.audience() == Audience.CANDIDATE)
                .toList();
        assertThat(candidateMessages).singleElement().satisfies(message -> {
            assertThat(message.subject()).isEqualTo("Your Technical interview for Backend Engineer");
            assertThat(message.body()).contains("Hi Priya").contains("Tue 13 Oct 2026, 11:00");
            assertThat(message.recipient().email()).isEqualTo("priya@x.test");
        });
        assertThat(service.notifications("t1", asha, "all", 50).data())
                .singleElement()
                .satisfies(n -> assertThat(n.type()).isEqualTo("ASSIGNED"));
        assertThat(service.notifications("t1", recruiter, "all", 50).data())
                .singleElement()
                .satisfies(n -> assertThat(n.type()).isEqualTo("INTERVIEW_SCHEDULED"));
        assertThat(memory.messages.stream().filter(m -> m.audience() == Audience.STAFF))
                .hasSize(1);
    }

    @Test
    void preferencesTurnOffInAppAndEmailIndependently() {
        var matrix = new EnumMap<NotificationEventKey, Map<DeliveryChannel, Boolean>>(NotificationEventKey.class);
        matrix.put(
                NotificationEventKey.ASSIGNED_TO_JOB_ROUND,
                Map.of(DeliveryChannel.IN_APP, false, DeliveryChannel.EMAIL, false));
        service.updatePreferences("t1", asha, matrix, null, null);

        service.handleEvent("evt-2", "interview.scheduled.v1", "t1", "app-1", scheduled());

        assertThat(service.notifications("t1", asha, "all", 50).data()).isEmpty();
        assertThat(memory.messages.stream()
                        .filter(m -> m.audience() == Audience.STAFF
                                && m.recipient().email().equals("asha@x.test")))
                .isEmpty();
    }

    @Test
    void digestUsersGetOneSummaryEmailInsteadOfImmediateEmails() {
        service.updatePreferences("t1", recruiter, Map.of(), QuietHours.off(), NotificationPreferences.Digest.HOURLY);

        service.handleEvent("evt-3", "interview.scheduled.v1", "t1", "app-1", scheduled());
        assertThat(memory.messages.stream()
                        .filter(m -> "r@x.test".equals(m.recipient().email())))
                .isEmpty();
        assertThat(memory.digest).hasSize(1);

        assertThat(service.sendDueDigests()).isEqualTo(1);
        assertThat(memory.messages.stream()
                        .filter(m -> "r@x.test".equals(m.recipient().email())))
                .singleElement()
                .satisfies(m -> assertThat(m.body()).contains("Technical booked with Priya Das"));
        assertThat(memory.digest).isEmpty();
    }

    @Test
    void aStaffMessageIsAuditedWithItsChannelAndNoMessageContent() {
        var conversation = service.startConversation("t1", "app-1");
        service.send("t1", conversation.id(), "Can you do Tuesday, Priya?", Channel.EMAIL, recruiter);

        assertThat(audit.payloads()).singleElement().satisfies(payload -> {
            assertThat(payload).containsEntry("action", "STAFF_MESSAGE_SENT");
            assertThat(payload.get("actor")).isEqualTo(Map.of("type", "USER", "id", "sub-r", "name", "Riya Recruiter"));
            assertThat(payload.get("resource")).isEqualTo(Map.of("type", "Conversation", "id", conversation.id()));
            assertThat(payload).containsEntry("applicationId", "app-1");
            assertThat(payload.get("details"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("channel", "EMAIL");
        });
        assertThat(audit.rawJson().getFirst())
                .doesNotContain("Tuesday")
                .doesNotContain("priya@x.test")
                .doesNotContain("Priya Das");
    }

    @Test
    void aTemplateChangeIsAuditedWithItsVersion() {
        service.updateTemplate(
                "t1", "not-moving-forward", List.of(Channel.EMAIL), "Update", "Hi {{candidate.firstName}}", recruiter);

        assertThat(audit.payloads()).singleElement().satisfies(payload -> {
            assertThat(payload).containsEntry("action", "MESSAGE_TEMPLATE_CHANGED");
            assertThat(payload.get("resource"))
                    .isEqualTo(Map.of("type", "MessageTemplate", "id", "not-moving-forward"));
            assertThat(payload.get("details"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("version", 2);
        });
        assertThat(audit.rawJson().getFirst()).doesNotContain("Hi {{");
    }

    @Test
    void aFailingAuditStoreNeverBreaksTheBusinessAction() {
        var broken = new CommunicationService(
                memory.messageStore,
                memory.conversationStore,
                memory.templateStore,
                memory.notificationStore,
                memory.preferenceStore,
                memory.candidateStore,
                memory.digestStore,
                memory.contacts,
                memory.drafter,
                new CommunicationService.Settings("Northwind", "Asia/Kolkata"),
                clock,
                AuditProbe.broken().recorder());
        var conversation = broken.startConversation("t1", "app-1");

        assertThat(broken.send("t1", conversation.id(), "Hello", Channel.EMAIL, recruiter))
                .isNotNull();
        assertThat(broken.updateTemplate(
                                "t1",
                                "not-moving-forward",
                                List.of(Channel.EMAIL),
                                "S",
                                "Hi {{candidate.firstName}}",
                                recruiter)
                        .version())
                .isEqualTo(2);
    }

    @Test
    void aCandidateReplyIsThreadedAndAlertsTheLastRecruiterWhoWrote() {
        var conversation = service.startConversation("t1", "app-1");
        service.send("t1", conversation.id(), "Can you do Tuesday?", null, recruiter);

        service.candidateReply("t1", "app-1", "valid-receipt", "Tuesday works.");

        var thread = service.conversation("t1", conversation.id());
        assertThat(thread.messages())
                .extracting(m -> m.from() + ":" + m.text())
                .containsExactly("me:Can you do Tuesday?", "them:Tuesday works.");
        assertThat(service.notifications("t1", recruiter, "unread", 50).data())
                .singleElement()
                .satisfies(n -> assertThat(n.type()).isEqualTo("CANDIDATE_REPLIED"));
        var portal = service.candidateThread("t1", "app-1", "valid-receipt");
        assertThat(portal.messages()).extracting(m -> m.from()).containsExactly("them", "me");
    }

    @Test
    void anInvalidReceiptCannotReadOrWriteTheThread() {
        assertThatThrownBy(() -> service.candidateThread("t1", "app-1", "forged"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.candidateReply("t1", "app-1", "forged", "hi"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void whatsAppNeedsAPhoneNumber() {
        memory.phone = null;
        var conversation = service.startConversation("t1", "app-1");

        assertThatThrownBy(() -> service.send("t1", conversation.id(), "Hello", Channel.WHATSAPP, recruiter))
                .hasMessageContaining("phone");
        assertThatThrownBy(() ->
                        service.updateCandidatePreferences("t1", "app-1", "valid-receipt", Channel.SMS, null, false))
                .hasMessageContaining("phone");
    }

    @Test
    void notificationsAreScopedToTheirRecipientAndCanBeMarkedRead() {
        service.handleEvent("evt-4", "interview.scheduled.v1", "t1", "app-1", scheduled());
        var mine = service.notifications("t1", asha, "all", 50).data().getFirst();

        assertThatThrownBy(() -> service.markRead("t1", mine.id(), recruiter)).hasMessageContaining("not found");
        service.markRead("t1", mine.id(), asha);
        assertThat(service.unreadCount("t1", asha)).isZero();
        assertThat(service.notifications("t1", asha, "mentions", 50).data()).hasSize(1);
    }

    @Test
    void templatesAreSeededPerTenantAndEditsCreateVersions() {
        var templates = service.templates("t1");
        assertThat(templates)
                .extracting(CommunicationViews.TemplateView::name)
                .contains("Application received", "Not moving forward");

        var edited = service.updateTemplate(
                "t1",
                "not-moving-forward",
                List.of(Channel.EMAIL),
                "Update on {{job.title}}",
                "Hi {{candidate.firstName}}",
                recruiter);
        assertThat(edited.version()).isEqualTo(2);
        assertThat(service.preview("t1", "not-moving-forward", Map.of()).subject())
                .isEqualTo("Update on Senior Java Backend Engineer");
        assertThat(service.templates("t2")).isNotEmpty();
    }

    @Test
    void anAiDraftUsesTheLastTenTurnsAndOnlyTheFirstNameAndSendsNothing() {
        var conversation = service.startConversation("t1", "app-1");
        for (int i = 1; i <= 7; i++) {
            service.send("t1", conversation.id(), "Question " + i, null, recruiter);
            service.candidateReply("t1", "app-1", "valid-receipt", "Answer " + i);
        }
        int before = memory.messages.size();

        var draft = service.aiDraft("t1", conversation.id());

        assertThat(draft).isEqualTo("Thanks Priya, Tuesday works.");
        assertThat(memory.messages).hasSize(before);
        var request = memory.lastDraft;
        assertThat(request.candidateFirstName()).isEqualTo("Priya");
        assertThat(request.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(request.thread()).hasSize(10);
        assertThat(request.thread().getLast().from()).isEqualTo("them");
        assertThat(request.thread().getLast().text()).isEqualTo("Answer 7");
        assertThat(request.thread().getFirst().from()).isEqualTo("me");
        assertThat(request.toString())
                .doesNotContain("Das")
                .doesNotContain("priya@x.test")
                .doesNotContain("+9198");
    }

    @Test
    void anUnavailableGatewayKeepsTheReplyBoxManual() {
        var conversation = service.startConversation("t1", "app-1");
        memory.draftingDown = true;

        assertThatThrownBy(() -> service.aiDraft("t1", conversation.id()))
                .isInstanceOf(CommunicationService.Unavailable.class)
                .hasMessageContaining("AI drafting is unavailable right now");
    }

    @Test
    void aTemplateSentOnAPersonsClickRespectsChannelAndMarketingOptOut() {
        var sent = service.sendTemplate(
                "t1", "app-1", "talent-reengagement", Map.of("job.title", "Staff Engineer"), "recruiter-1");

        assertThat(sent.sent()).isTrue();
        assertThat(memory.messages).singleElement().satisfies(message -> {
            assertThat(message.body()).contains("Hi Priya").contains("Staff Engineer");
            assertThat(message.actorId()).isEqualTo("recruiter-1");
            assertThat(message.templateKey()).isEqualTo("talent-reengagement");
        });

        service.updateCandidatePreferences("t1", "app-1", "valid-receipt", Channel.EMAIL, null, true);
        var blocked = service.sendTemplate("t1", "app-1", "talent-reengagement", Map.of(), "recruiter-1");

        assertThat(blocked.sent()).isFalse();
        assertThat(blocked.reason()).isEqualTo("MARKETING_OPT_OUT");
        assertThat(memory.messages).hasSize(1);
    }

    /** In-memory fakes of every port, sharing state so assertions can inspect what was stored. */
    final class Memory {
        final List<Message> messages = new ArrayList<>();
        final Map<String, Conversation> conversations = new HashMap<>();
        final Map<String, MessageTemplate> templates = new HashMap<>();
        final List<StaffNotification> notifications = new ArrayList<>();
        final Map<String, Instant> reads = new HashMap<>();
        final Map<String, NotificationPreferences> preferences = new HashMap<>();
        final Map<String, CandidatePreferences> candidates = new HashMap<>();
        final List<DigestItem> digest = new ArrayList<>();
        final Set<String> dedupe = new java.util.HashSet<>();
        String phone = "+919800000000";
        DraftRequest lastDraft;
        boolean draftingDown;

        final ReplyDrafter drafter = (tenantId, request) -> {
            lastDraft = request;
            if (draftingDown) throw new DraftingUnavailableException("down", null);
            return "  Thanks Priya, Tuesday works.  ";
        };

        final MessageStore messageStore = new MessageStore() {
            public boolean insert(Message message) {
                var key = (String) field(message, "dedupeKey");
                if (key != null && !dedupe.add("m" + key)) return false;
                messages.add(message);
                return true;
            }

            public Optional<Message> find(String messageId) {
                return messages.stream().filter(m -> m.id().equals(messageId)).findFirst();
            }

            public List<Message> conversation(String tenantId, String conversationId, int limit) {
                return messages.stream()
                        .filter(m -> conversationId.equals(m.conversationId()))
                        .toList();
            }

            public void updateStatus(Message message) {}
        };

        final ConversationStore conversationStore = new ConversationStore() {
            public Optional<Conversation> find(String tenantId, String id) {
                return Optional.ofNullable(conversations.get(id));
            }

            public Conversation save(Conversation conversation) {
                conversations.put(conversation.id(), conversation);
                return conversation;
            }

            public List<Conversation> recent(String tenantId, int limit) {
                return List.copyOf(conversations.values());
            }
        };

        final TemplateStore templateStore = new TemplateStore() {
            public Optional<MessageTemplate> find(String tenantId, String key) {
                return Optional.ofNullable(templates.get(MessageTemplate.idFor(tenantId, key)));
            }

            public List<MessageTemplate> all(String tenantId) {
                return templates.values().stream()
                        .filter(t -> t.tenantId().equals(tenantId))
                        .toList();
            }

            public MessageTemplate save(MessageTemplate template) {
                templates.put(template.id(), template);
                return template;
            }

            public void insertIfAbsent(MessageTemplate template) {
                templates.putIfAbsent(template.id(), template);
            }
        };

        final NotificationStore notificationStore = new NotificationStore() {
            public boolean insert(StaffNotification notification) {
                var key = (String) field(notification, "dedupeKey");
                if (key != null && !dedupe.add("n" + key)) return false;
                notifications.add(notification);
                return true;
            }

            public List<StaffNotification> list(
                    String tenantId, Collection<String> recipients, String filter, int limit) {
                return notifications.stream()
                        .filter(n -> recipients.contains(n.recipient()))
                        .filter(n -> !"unread".equals(filter) || !reads.containsKey(n.id()))
                        .filter(n -> !"mentions".equals(filter) || n.mention())
                        .toList();
            }

            public long unread(String tenantId, Collection<String> recipients) {
                return list(tenantId, recipients, "unread", 100).size();
            }

            public boolean markRead(String tenantId, String id, Collection<String> recipients) {
                var match =
                        notifications.stream().anyMatch(n -> n.id().equals(id) && recipients.contains(n.recipient()));
                if (match) reads.put(id, clock.instant());
                return match;
            }

            public void markAllRead(String tenantId, Collection<String> recipients) {
                list(tenantId, recipients, "unread", 100).forEach(n -> reads.put(n.id(), clock.instant()));
            }
        };

        final PreferenceStore preferenceStore = new PreferenceStore() {
            public Optional<NotificationPreferences> find(String tenantId, String subject) {
                return Optional.ofNullable(preferences.get(NotificationPreferences.idFor(tenantId, subject)));
            }

            public Optional<NotificationPreferences> findByEmail(String tenantId, String email) {
                return preferences.values().stream()
                        .filter(p -> email.equals(p.email()))
                        .findFirst();
            }

            public NotificationPreferences save(NotificationPreferences value) {
                preferences.put(value.id(), value);
                return value;
            }

            public List<NotificationPreferences> withDigest() {
                return preferences.values().stream()
                        .filter(p -> p.emailDigest() != NotificationPreferences.Digest.IMMEDIATE)
                        .toList();
            }
        };

        final CandidatePreferenceStore candidateStore = new CandidatePreferenceStore() {
            public Optional<CandidatePreferences> find(String tenantId, String candidateId) {
                return Optional.ofNullable(candidates.get(CandidatePreferences.idFor(tenantId, candidateId)));
            }

            public CandidatePreferences save(CandidatePreferences value) {
                candidates.put(value.id(), value);
                return value;
            }
        };

        final DigestStore digestStore = new DigestStore() {
            public void add(DigestItem item) {
                digest.add(item);
            }

            public List<DigestItem> take(String tenantId, String subject) {
                var items = List.copyOf(digest);
                digest.clear();
                return items;
            }
        };

        final ContactDirectory contacts = new ContactDirectory() {
            public Contact contact(String tenantId, String applicationId) {
                return new Contact(applicationId, "cand-1", "Priya Das", "priya@x.test", phone, "Backend Engineer");
            }

            public Contact verifyReceipt(String tenantId, String applicationId, String receipt) {
                if (!"valid-receipt".equals(receipt))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid application receipt");
                return contact(tenantId, applicationId);
            }
        };

        private Object field(Object target, String name) {
            try {
                var field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
