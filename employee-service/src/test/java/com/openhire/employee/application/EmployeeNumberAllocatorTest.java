package com.openhire.employee.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.employee.application.Memory.MutableClock;
import com.openhire.employee.application.Memory.Numbers;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class EmployeeNumberAllocatorTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-31T23:00:00Z"));

    @Test
    void formatsPrefixYearAndFiveDigitSequence() {
        var allocator = new EmployeeNumberAllocator(new Numbers(), "EMP", clock);

        assertThat(allocator.allocate("t1")).isEqualTo("EMP-2026-00001");
        assertThat(allocator.allocate("t1")).isEqualTo("EMP-2026-00002");
    }

    @Test
    void sequencesAreSeparatePerTenantAndRestartEachYear() {
        var allocator = new EmployeeNumberAllocator(new Numbers(), "EMP", clock);
        allocator.allocate("t1");

        assertThat(allocator.allocate("t2")).isEqualTo("EMP-2026-00001");
        clock.advance(Duration.ofHours(2));
        assertThat(allocator.allocate("t1")).isEqualTo("EMP-2027-00001");
    }

    @Test
    void concurrentAllocationYieldsUniqueNumbers() throws Exception {
        var allocator = new EmployeeNumberAllocator(new Numbers(), "EMP", clock);
        var pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<String>> work = java.util.stream.IntStream.range(0, 200)
                    .<Callable<String>>mapToObj(i -> () -> allocator.allocate("t1"))
                    .toList();
            var numbers = new HashSet<String>();
            for (var future : pool.invokeAll(work)) numbers.add(future.get());

            assertThat(numbers).hasSize(200).contains("EMP-2026-00001", "EMP-2026-00200");
        } finally {
            pool.shutdownNow();
        }
    }
}
