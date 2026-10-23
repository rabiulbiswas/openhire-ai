package com.openhire.employee.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import com.openhire.employee.domain.HireSaga;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Opt-in: creates and drops only a uniquely named oh_phase5_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoEmployeeNumbersTest {
    private static MongoClient client;
    private static MongoTemplate mongo;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase5_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void concurrentAllocationYieldsUniqueGaplessNumbersPerTenantAndYear() throws Exception {
        var numbers = new MongoEmployeeNumbers(mongo);
        var pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Long>> work = java.util.stream.IntStream.range(0, 100)
                    .<Callable<Long>>mapToObj(i -> () -> numbers.next("t1", 2026))
                    .toList();
            var seen = new HashSet<Long>();
            for (var future : pool.invokeAll(work)) seen.add(future.get());

            assertThat(seen).hasSize(100).contains(1L, 100L);
            assertThat(numbers.next("t2", 2026)).isEqualTo(1L);
            assertThat(numbers.next("t1", 2027)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneWorkerCanLeaseADueSaga() throws Exception {
        var sagas = new MongoSagaRepository(mongo);
        var now = Instant.parse("2026-10-15T09:00:00Z");
        sagas.insertIfAbsent(HireSaga.start(
                new OfferAccepted("t1", "off-1", "app-1", "c", "n", "j", "t", "o", "2026-11-02", "d", "l", "r"), now));
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> work = java.util.stream.IntStream.range(0, 8)
                    .<Callable<Boolean>>mapToObj(i -> () -> sagas.claimNext(now, Duration.ofSeconds(60), "w" + i)
                            .isPresent())
                    .toList();
            long claimed = 0;
            for (var future : pool.invokeAll(work)) if (future.get()) claimed++;

            assertThat(claimed).isEqualTo(1);
            assertThat(sagas.claimNext(now.plusSeconds(61), Duration.ofSeconds(60), "late"))
                    .isPresent();
        } finally {
            pool.shutdownNow();
        }
    }
}
