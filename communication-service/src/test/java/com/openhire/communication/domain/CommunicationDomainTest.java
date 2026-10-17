package com.openhire.communication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.communication.domain.CommunicationTypes.Audience;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.DeliveryChannel;
import com.openhire.communication.domain.CommunicationTypes.MessageStatus;
import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import com.openhire.communication.domain.CommunicationTypes.Recipient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommunicationDomainTest {
    static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");

    @Test
    void templatesRenderKnownVariablesAndRejectUnknownOnes() {
        var template = MessageTemplate.create(
                "t1",
                "x",
                "X",
                List.of(Channel.EMAIL),
                "Hello {{candidate.firstName}}",
                "Role: {{ job.title }} {{meeting.url}}",
                "me",
                NOW);

        var rendered = template.render(Map.of("candidate.firstName", "Priya", "job.title", "Backend $1 Engineer"));

        assertThat(rendered.subject()).isEqualTo("Hello Priya");
        assertThat(rendered.body()).isEqualTo("Role: Backend $1 Engineer ");
        assertThatThrownBy(() -> template.revise(List.of(Channel.EMAIL), "Hi", "{{candidate.salary}}", "me", NOW))
                .hasMessageContaining("candidate.salary");
    }

    @Test
    void editingATemplateKeepsEarlierVersions() {
        var template = MessageTemplate.create("t1", "x", "X", List.of(Channel.EMAIL), "S1", "B1", "a", NOW);
        template.revise(List.of(Channel.EMAIL, Channel.WHATSAPP), "S2", "B2", "b", NOW.plusSeconds(60));

        assertThat(template.version()).isEqualTo(2);
        assertThat(template.history()).singleElement().satisfies(revision -> {
            assertThat(revision.version()).isEqualTo(1);
            assertThat(revision.body()).isEqualTo("B1");
        });
    }

    @Test
    void outboundMessagesCarryTheirDispatchCommandAndInboundOnesDoNot() {
        var out = Message.outbound(
                "t1",
                "conv",
                Audience.CANDIDATE,
                Channel.WHATSAPP,
                new Recipient("Priya", "p@x.test", "+911"),
                "S",
                "Body",
                "k",
                1,
                "sub",
                "dedupe",
                true,
                new QuietHours(true, "21:00", "08:00", "Asia/Kolkata"),
                NOW);
        var in = Message.inbound("t1", "conv", new Recipient("Priya", null, null), "Reply", NOW);

        var command = out.pendingEvents().getFirst();
        assertThat(command.eventType()).isEqualTo("communication.message.requested.v1");
        assertThat(command.payload())
                .containsEntry("channel", "WHATSAPP")
                .containsEntry("fallbackChannel", "EMAIL")
                .containsEntry("category", "TRANSACTIONAL")
                .containsKey("quietHours");
        assertThat(in.pendingEvents()).isEmpty();
        assertThat(in.status()).isEqualTo(MessageStatus.RECEIVED);
    }

    @Test
    void deliveryUpdatesNeverMoveAMessageBackwards() {
        var message = Message.outbound(
                "t1",
                null,
                Audience.STAFF,
                Channel.EMAIL,
                new Recipient(null, "s@x.test", null),
                "S",
                "B",
                null,
                null,
                null,
                null,
                false,
                null,
                NOW);

        assertThat(message.applyDelivery(MessageStatus.SENT, null, NOW)).isTrue();
        assertThat(message.applyDelivery(MessageStatus.DEFERRED, "quiet hours", NOW))
                .isFalse();
        assertThat(message.applyDelivery(MessageStatus.DELIVERED, null, NOW)).isTrue();
        assertThat(message.applyDelivery(MessageStatus.FAILED, "bounce", NOW)).isFalse();
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void preferencesDefaultToInAppEverywhereAndEmailForActionableEvents() {
        var prefs = NotificationPreferences.defaults("t1", "sub", "Me@X.test", "Asia/Kolkata");

        assertThat(prefs.email()).isEqualTo("me@x.test");
        assertThat(prefs.allows(NotificationEventKey.SCORECARD_OVERDUE, DeliveryChannel.EMAIL))
                .isTrue();
        assertThat(prefs.allows(NotificationEventKey.NEW_APPLICATIONS_SCORED, DeliveryChannel.EMAIL))
                .isFalse();
        assertThat(prefs.allows(NotificationEventKey.NEW_APPLICATIONS_SCORED, DeliveryChannel.IN_APP))
                .isTrue();
        assertThat(prefs.allows(NotificationEventKey.CANDIDATE_REPLIED, DeliveryChannel.SLACK))
                .isFalse();
    }

    @Test
    void dailyDigestIsDueOnceAfterNineInTheUsersTimeZone() {
        var prefs = NotificationPreferences.defaults("t1", "sub", "me@x.test", "Asia/Kolkata");
        prefs.update(Map.of(), QuietHours.off(), NotificationPreferences.Digest.DAILY_9AM, NOW);

        var eightThirtyIst = Instant.parse("2026-10-12T03:00:00Z");
        var nineFifteenIst = Instant.parse("2026-10-12T03:45:00Z");
        assertThat(prefs.digestDue(eightThirtyIst)).isFalse();
        assertThat(prefs.digestDue(nineFifteenIst)).isTrue();
        prefs.markDigestSent(nineFifteenIst);
        assertThat(prefs.digestDue(nineFifteenIst.plusSeconds(3600))).isFalse();
        assertThat(prefs.digestDue(nineFifteenIst.plusSeconds(86_400))).isTrue();
    }

    @Test
    void candidatesUseTheirPreferredChannelOnlyWhenTheyHaveAPhone() {
        var prefs = CandidatePreferences.defaults("t1", "cand");
        prefs.update(Channel.WHATSAPP, null, false, NOW);

        assertThat(prefs.channelFor(true)).isEqualTo(Channel.WHATSAPP);
        assertThat(prefs.channelFor(false)).isEqualTo(Channel.EMAIL);
    }
}
