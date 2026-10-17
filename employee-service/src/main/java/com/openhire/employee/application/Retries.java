package com.openhire.employee.application;

import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;

/** Re-runs a read-modify-write when another writer changed the document in between. */
final class Retries {
    private static final int ATTEMPTS = 5;

    private Retries() {}

    static <T> T optimistic(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException conflict) {
                if (attempt == ATTEMPTS) throw conflict;
            }
        }
    }
}
