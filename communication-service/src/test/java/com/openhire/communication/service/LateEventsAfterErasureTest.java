package com.openhire.communication.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openhire.communication.AuditProbe;
import com.openhire.communication.service.port.CommunicationPorts.CandidatePreferenceStore;
import com.openhire.communication.service.port.CommunicationPorts.Contact;
import com.openhire.communication.service.port.CommunicationPorts.ContactDirectory;
import com.openhire.communication.service.port.CommunicationPorts.ConversationStore;
import com.openhire.communication.service.port.CommunicationPorts.DigestStore;
import com.openhire.communication.service.port.CommunicationPorts.MessageStore;
import com.openhire.communication.service.port.CommunicationPorts.NotificationStore;
import com.openhire.communication.service.port.CommunicationPorts.PreferenceStore;
import com.openhire.communication.service.port.CommunicationPorts.ReplyDrafter;
import com.openhire.communication.service.port.CommunicationPorts.TemplateStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LateEventsAfterErasureTest {
    @Test
    void aLifecycleEventForAnErasedCandidateCreatesNothing() {
        var messages = mock(MessageStore.class);
        var conversations = mock(ConversationStore.class);
        var notifications = mock(NotificationStore.class);
        var contacts = mock(ContactDirectory.class);
        when(contacts.contact("t1", "app-1"))
                .thenReturn(new Contact("app-1", "cand-1", "Erased candidate", null, null, "Engineer"));
        var service = new CommunicationService(
                messages,
                conversations,
                mock(TemplateStore.class),
                notifications,
                mock(PreferenceStore.class),
                mock(CandidatePreferenceStore.class),
                mock(DigestStore.class),
                contacts,
                mock(ReplyDrafter.class),
                new CommunicationService.Settings("Northwind", "UTC"),
                Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("UTC")),
                AuditProbe.working().recorder());
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-1", "req-1");
        service.setTombstones(tombstones);

        service.handleEvent("ev-1", "ApplicationSubmitted", "t1", "app-1", Map.of());

        verifyNoInteractions(messages, conversations, notifications);
    }
}
