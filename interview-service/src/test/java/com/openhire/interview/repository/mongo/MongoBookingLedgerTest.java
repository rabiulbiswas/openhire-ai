package com.openhire.interview.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.interview.service.port.InterviewPorts.BookingConflict;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Opt-in: creates and drops only a uniquely named oh_phase4_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoBookingLedgerTest {
    private static MongoClient client;
    private static MongoTemplate mongo;
    private static MongoBookingLedger ledger;
    private static final Instant TEN = Instant.parse("2026-10-13T04:30:00Z");
    private static final Duration HOUR = Duration.ofHours(1);

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase4_test_" + UUID.randomUUID().toString().replace("-", ""));
        ledger = new MongoBookingLedger(mongo);
        ledger.indexes();
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void overlappingBookingsForTheSameInterviewerConflictButAdjacentOnesDoNot() {
        ledger.reserve("t1", "round-a", List.of("email:asha@x.test"), TEN, TEN.plus(HOUR));

        assertThatThrownBy(() -> ledger.reserve(
                        "t1",
                        "round-b",
                        List.of("email:asha@x.test"),
                        TEN.plus(Duration.ofMinutes(45)),
                        TEN.plus(Duration.ofMinutes(105))))
                .isInstanceOf(BookingConflict.class)
                .hasMessageContaining("asha@x.test");
        ledger.reserve(
                "t1",
                "round-c",
                List.of("email:asha@x.test"),
                TEN.plus(HOUR),
                TEN.plus(HOUR).plus(HOUR));
        ledger.reserve("t2", "round-d", List.of("email:asha@x.test"), TEN, TEN.plus(HOUR));
    }

    @Test
    void aFailedMultiInterviewerReservationLeavesNothingBehind() {
        var start = TEN.plus(Duration.ofDays(1));
        ledger.reserve("t1", "busy-ben", List.of("email:ben@x.test"), start, start.plus(HOUR));

        assertThatThrownBy(() -> ledger.reserve(
                        "t1", "panel", List.of("email:cara@x.test", "email:ben@x.test"), start, start.plus(HOUR)))
                .isInstanceOf(BookingConflict.class);
        assertThat(ledger.busy("t1", List.of("email:cara@x.test"), start.minus(HOUR), start.plus(HOUR)))
                .isEmpty();
    }

    @Test
    void concurrentAttemptsForTheSameTimeProduceExactlyOneBooking() throws Exception {
        var start = TEN.plus(Duration.ofDays(2));
        var tasks = new java.util.ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 12; i++) {
            var round = "race-" + i;
            tasks.add(() -> {
                try {
                    ledger.reserve("t1", round, List.of("email:dev@x.test"), start, start.plus(HOUR));
                    return true;
                } catch (BookingConflict conflict) {
                    return false;
                }
            });
        }
        try (var pool = Executors.newFixedThreadPool(12)) {
            var won = pool.invokeAll(tasks).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .count();
            assertThat(won).isEqualTo(1);
        }
        assertThat(ledger.busy("t1", List.of("email:dev@x.test"), start, start.plus(HOUR)))
                .hasSize(4);
    }

    @Test
    void retainKeepsTheNewBookingAndDropsTheOldOne() {
        var first = TEN.plus(Duration.ofDays(3));
        var moved = first.plus(Duration.ofMinutes(30));
        ledger.reserve("t1", "move", List.of("email:eve@x.test"), first, first.plus(HOUR));
        ledger.reserve("t1", "move", List.of("email:eve@x.test"), moved, moved.plus(HOUR));
        ledger.retain("t1", "move", List.of("email:eve@x.test"), moved, moved.plus(HOUR));

        assertThat(ledger.busy("t1", List.of("email:eve@x.test"), first, first.plus(Duration.ofMinutes(30))))
                .isEmpty();
        assertThat(ledger.busy("t1", List.of("email:eve@x.test"), moved, moved.plus(HOUR)))
                .hasSize(4);
        ledger.release("t1", "move");
        assertThat(ledger.busy("t1", List.of("email:eve@x.test"), first, moved.plus(HOUR)))
                .isEmpty();
    }
}
