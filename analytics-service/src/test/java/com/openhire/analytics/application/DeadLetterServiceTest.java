package com.openhire.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.analytics.application.DeadLetterService.AlreadyReplayed;
import com.openhire.analytics.application.DeadLetterService.NotFound;
import com.openhire.analytics.application.DeadLetterService.Operator;
import com.openhire.analytics.application.DeadLetterService.RateLimited;
import com.openhire.analytics.application.Memory.DeadLetters;
import com.openhire.analytics.application.Memory.MutableClock;
import com.openhire.analytics.application.Memory.Replays;
import com.openhire.analytics.application.port.AnalyticsPorts.AuditTrail;
import com.openhire.analytics.application.port.AnalyticsPorts.Republisher;
import com.openhire.analytics.domain.DeadLetterRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeadLetterServiceTest {
    private static final Instant T = Instant.parse("2026-10-15T10:00:00Z");

    record Published(String topic, String key, String value, Map<String, String> headers) {}

    record Audited(String tenant, String actor, String name, String id, String topic) {}

    private final DeadLetters source = new DeadLetters();
    private final Replays replays = new Replays();
    private final List<Published> published = new ArrayList<>();
    private final List<Audited> audited = new ArrayList<>();
    private final MutableClock clock = new MutableClock(T.plusSeconds(600));
    private boolean brokerDown;
    private int perMinute = 3;

    private final Republisher republisher = (topic, key, value, headers) -> {
        if (brokerDown) throw new IllegalStateException("broker down");
        published.add(new Published(topic, key, value, headers));
    };
    private final AuditTrail audit =
            (tenant, actor, name, id, topic) -> audited.add(new Audited(tenant, actor, name, id, topic));

    private final Operator org1 = new Operator("admin-1", "Asha Admin", "t1", false);
    private final Operator super1 = new Operator("root", "Root", "", true);

    private DeadLetterService service() {
        return new DeadLetterService(
                source, republisher, replays, audit, JsonMapper.builder().build(), clock, perMinute, 200);
    }

    private static String value(String tenant, String messageId) {
        return """
                {"eventId":"e-%s","eventType":"communication.message.requested.v1","tenantId":"%s",
                "payload":{"messageId":"%s","applicationId":"app-9","channel":"EMAIL","body":"Hello Priya, your offer",
                "subject":"Offer","recipient":{"name":"Priya Das","email":"priya@example.com","phone":"+911234567890"}}}
                """
                .formatted(messageId, tenant, messageId);
    }

    private DeadLetterRecord dead(
            String tenant, int partition, long offset, int secondsAfter, Map<String, String> extra) {
        var headers = new HashMap<String, String>();
        headers.put("kafka_dlt-original-topic", "communication.commands.v1");
        headers.put("kafka_dlt-original-consumer-group", "notification-dispatcher-v1");
        headers.put("kafka_dlt-exception-message", "Provider rejected priya@example.com");
        headers.put("kafka_dlt-exception-fqcn", "java.lang.IllegalStateException");
        headers.put("kafka_dlt-original-partition", "2");
        headers.put("kafka_dlt-original-offset", "77");
        headers.put(
                "kafka_dlt-original-timestamp",
                String.valueOf(T.plusSeconds(secondsAfter - 30).toEpochMilli()));
        headers.putAll(extra);
        var record = new DeadLetterRecord(
                "communication.commands.v1.DLT",
                partition,
                offset,
                T.plusSeconds(secondsAfter),
                "msg-" + offset,
                value(tenant, "m" + offset),
                headers);
        source.records.add(record);
        return record;
    }

    private DeadLetterRecord dead(String tenant, long offset) {
        return dead(tenant, 0, offset, (int) offset, Map.of());
    }

    @Test
    void mapsSpringHeadersAndParsesTheEnvelope() {
        var record = dead("t1", 0, 5, 100, Map.of());

        var letter = service().list(org1, 10).getFirst();

        assertThat(letter.id()).isEqualTo("communication.commands.v1.DLT:0:5");
        assertThat(letter.originalTopic()).isEqualTo("communication.commands.v1");
        assertThat(letter.consumerGroup()).isEqualTo("notification-dispatcher-v1");
        assertThat(letter.eventType()).isEqualTo("communication.message.requested.v1");
        assertThat(letter.tenantId()).isEqualTo("t1");
        assertThat(letter.firstFailedAt()).isEqualTo(T.plusSeconds(70).toString());
        assertThat(letter.replayedAt()).isNull();
        assertThat(record.id()).isEqualTo(letter.id());
    }

    @Test
    void previewsAndDetailsNeverShowPersonalData() {
        dead("t1", 0, 5, 100, Map.of());

        var service = service();
        var letter = service.list(org1, 10).getFirst();
        var detail = service.detail(org1, "communication.commands.v1.DLT:0:5");

        for (var text :
                List.of(letter.payloadPreview(), letter.error(), String.valueOf(detail.payload()), detail.error()))
            assertThat(text)
                    .doesNotContain("Priya")
                    .doesNotContain("priya@")
                    .doesNotContain("+9112")
                    .doesNotContain("offer");
        assertThat(letter.payloadPreview()).contains("m5").contains("app-9").contains("***");
        assertThat(String.valueOf(detail.payload())).contains("app-9").contains("EMAIL");
        assertThat(detail.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(detail.originalPartition()).isEqualTo(2);
        assertThat(detail.originalOffset()).isEqualTo(77L);
        assertThat(detail.deadLetterTopic()).isEqualTo("communication.commands.v1.DLT");
    }

    @Test
    void aPayloadThatIsNotJsonIsNeverShown() {
        source.records.add(new DeadLetterRecord(
                "x.DLT", 0, 1, T, null, "Priya Das lives at 12 MG Road", Map.of("kafka_dlt-original-topic", "x")));

        var letter = service().list(super1, 10).getFirst();
        var detail = service().detail(super1, "x.DLT:0:1");

        assertThat(letter.payloadPreview()).doesNotContain("Priya");
        assertThat(String.valueOf(detail.payload())).doesNotContain("Priya");
        assertThat(letter.eventType()).isEmpty();
    }

    @Test
    void anOrgAdminSeesOnlyItsTenantAndASuperAdminSeesAll() {
        dead("t1", 1);
        dead("t2", 2);
        dead("", 3);
        var service = service();

        assertThat(service.list(org1, 10)).extracting("tenantId").containsExactly("t1");
        assertThat(service.list(super1, 10)).extracting("tenantId").containsExactlyInAnyOrder("t1", "t2", "");
        assertThatThrownBy(() -> service.detail(org1, "communication.commands.v1.DLT:0:2"))
                .isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> service.replay(org1, "communication.commands.v1.DLT:0:3", "valid reason", false))
                .isInstanceOf(NotFound.class);
        assertThat(service.detail(super1, "communication.commands.v1.DLT:0:3").tenantId())
                .isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void listsNewestFirstAndHonoursTheLimit() {
        dead("t1", 1);
        dead("t1", 2);
        dead("t1", 3);

        assertThat(service().list(org1, 2))
                .extracting("id")
                .containsExactly("communication.commands.v1.DLT:0:3", "communication.commands.v1.DLT:0:2");
    }

    @Test
    void unknownOrMalformedIdsAreNotFound() {
        var service = service();

        for (var id : List.of("nope", "a:b:c", "communication.commands.v1.DLT:0:99", "a:1", "a:1:2:3"))
            assertThatThrownBy(() -> service.detail(super1, id)).isInstanceOf(NotFound.class);
    }

    @Test
    void replayRepublishesTheOriginalValueAndKeyToTheOriginalTopicOnceAndAudits() {
        var record = dead("t1", 5);
        var service = service();

        var result = service.replay(org1, record.id(), "  Provider fixed, retry  ", false);

        assertThat(published).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo("communication.commands.v1");
            assertThat(message.key()).isEqualTo("msg-5");
            assertThat(message.value()).isEqualTo(record.value());
            assertThat(message.headers()).containsEntry("x-replayed-from", record.id());
        });
        assertThat(audited)
                .containsExactly(new Audited("t1", "admin-1", "Asha Admin", record.id(), "communication.commands.v1"));
        assertThat(result.replayCount()).isEqualTo(1);
        var stored = replays.rows.get(record.id());
        assertThat(stored.reason()).isEqualTo("Provider fixed, retry");
        assertThat(stored.replayedBy()).isEqualTo("admin-1");
        var listed = service.list(org1, 10).getFirst();
        assertThat(listed.replayedAt()).isEqualTo(clock.instant().toString());
        assertThat(listed.replayedBy()).isEqualTo("admin-1");

        assertThatThrownBy(() -> service.replay(org1, record.id(), "second attempt", false))
                .isInstanceOf(AlreadyReplayed.class);
        assertThat(published).hasSize(1);
        assertThat(audited).hasSize(1);
    }

    @Test
    void onlyASuperAdminCanForceAnotherReplay() {
        var record = dead("t1", 5);
        var service = service();
        service.replay(org1, record.id(), "first replay", false);

        assertThatThrownBy(() -> service.replay(org1, record.id(), "force it", true))
                .isInstanceOf(AlreadyReplayed.class);
        var forced = service.replay(super1, record.id(), "force it", true);

        assertThat(forced.replayCount()).isEqualTo(2);
        assertThat(published).hasSize(2);
        assertThat(audited).hasSize(2);
        assertThat(audited.getLast().tenant()).isEqualTo("t1");
        assertThat(replays.rows.get(record.id()).forced()).isTrue();
        assertThatThrownBy(() -> service.replay(super1, record.id(), "again without force", false))
                .isInstanceOf(AlreadyReplayed.class);
    }

    @Test
    void theReasonIsRequiredAndBounded() {
        var record = dead("t1", 5);
        var service = service();

        for (var reason : new String[] {null, "", "    ", "abcd", " ab  ", "x".repeat(501)})
            assertThatThrownBy(() -> service.replay(org1, record.id(), reason, false))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(published).isEmpty();
        assertThat(replays.rows).isEmpty();
        service.replay(org1, record.id(), "abcde", false);
        service.replay(super1, record.id(), "x".repeat(500), true);
        assertThat(published).hasSize(2);
    }

    @Test
    void aMessageFromARetryTopicGoesBackToTheTopicItWasRetriedFrom() {
        var retry = dead("t1", 0, 8, 8, Map.of("kafka_dlt-original-topic", "communication.commands.v1-retry-2"));
        var noHeader = new DeadLetterRecord("offer.events.v1-dlt", 0, 1, T, "k", value("t1", "m1"), Map.of());
        source.records.add(noHeader);
        var service = service();

        service.replay(org1, retry.id(), "retry topic message", false);
        service.replay(org1, noHeader.id(), "no header message", false);

        assertThat(published).extracting("topic").containsExactly("communication.commands.v1", "offer.events.v1");
    }

    @Test
    void aFailedRepublishLeavesNoClaimAndNoAudit() {
        var record = dead("t1", 5);
        var service = service();
        brokerDown = true;

        assertThatThrownBy(() -> service.replay(org1, record.id(), "broker was down", false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(replays.rows).isEmpty();
        assertThat(audited).isEmpty();

        brokerDown = false;
        service.replay(org1, record.id(), "broker is back", false);
        assertThat(published).hasSize(1);
    }

    @Test
    void replaysAreLimitedPerTenantPerMinute() {
        for (int i = 1; i <= 4; i++) dead("t1", i);
        dead("t2", 10);
        var service = service();
        for (int i = 1; i <= 3; i++) {
            service.replay(org1, "communication.commands.v1.DLT:0:" + i, "within the limit", false);
            clock.advance(Duration.ofSeconds(5));
        }

        assertThatThrownBy(() -> service.replay(org1, "communication.commands.v1.DLT:0:4", "over the limit", false))
                .isInstanceOfSatisfying(RateLimited.class, limited -> assertThat(limited.retryAfterSeconds())
                        .isBetween(1L, 60L));
        assertThat(published).hasSize(3);
        assertThat(replays.rows).doesNotContainKey("communication.commands.v1.DLT:0:4");
        // Another tenant has its own allowance.
        service.replay(super1, "communication.commands.v1.DLT:0:10", "other tenant", false);

        clock.advance(Duration.ofSeconds(61));
        service.replay(org1, "communication.commands.v1.DLT:0:4", "after the minute", false);
        assertThat(published).hasSize(5);
    }

    @Test
    void batchReplayHonoursDryRunLimitsTopicFilterAndSkipsWhatWasReplayed() {
        for (int i = 1; i <= 6; i++) dead("t1", i);
        var other = new DeadLetterRecord(
                "offer.events.v1.DLT",
                0,
                1,
                T.plusSeconds(1),
                "k",
                value("t1", "mx"),
                Map.of("kafka_dlt-original-topic", "offer.events.v1"));
        source.records.add(other);
        perMinute = 100;
        var service = service();
        service.replay(org1, "communication.commands.v1.DLT:0:1", "replayed earlier", false);
        published.clear();
        audited.clear();

        var dry = service.replayBatch(org1, "batch dry run", "communication.commands.v1", 50, true);
        assertThat(dry).isEqualTo(new DeadLetterViews.BatchResult(true, 5, 0));
        assertThat(published).isEmpty();

        var batch = service.replayBatch(org1, "batch for real", "communication.commands.v1", 3, false);
        assertThat(batch).isEqualTo(new DeadLetterViews.BatchResult(false, 3, 3));
        assertThat(published).extracting(Published::key).containsExactly("msg-2", "msg-3", "msg-4");
        assertThat(audited).hasSize(3);

        var rest = service.replayBatch(org1, "the rest of them", null, null, false);
        assertThat(rest.replayed()).isEqualTo(3);
        assertThat(service.replayBatch(org1, "nothing is left", null, 10, false).matched())
                .isZero();
    }

    @Test
    void batchValidatesReasonAndLimitAndStopsAtTheRateLimit() {
        for (int i = 1; i <= 5; i++) dead("t1", i);
        var service = service();

        assertThatThrownBy(() -> service.replayBatch(org1, "ok reason", null, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replayBatch(org1, "ok reason", null, 51, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replayBatch(org1, "no", null, 5, true))
                .isInstanceOf(IllegalArgumentException.class);

        var limited = service.replayBatch(org1, "five but only three allowed", null, 5, false);
        assertThat(limited).isEqualTo(new DeadLetterViews.BatchResult(false, 5, 3));
        assertThatThrownBy(() -> service.replayBatch(org1, "nothing allowed now", null, 5, false))
                .isInstanceOf(RateLimited.class);
    }

    @Test
    void anOrgAdminBatchNeverTouchesAnotherTenant() {
        dead("t1", 1);
        dead("t2", 2);
        perMinute = 100;

        var result = service().replayBatch(org1, "own tenant only", null, 50, false);

        assertThat(result.matched()).isEqualTo(1);
        assertThat(published).extracting(Published::key).containsExactly("msg-1");
    }
}
