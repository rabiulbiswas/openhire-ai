package com.openhire.communication.adapter.out.mongo;

import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Early builds used sparse compound dedupe indexes, which still index documents without a dedupe key and so
 * rejected a tenant's second manual message. The replacements are partial indexes; this removes the old ones.
 */
@Component
class LegacyIndexCleanup {
    private static final Map<String, String> LEGACY = Map.of("messages", "trigger_dedupe", "notifications", "dedupe");
    private final MongoTemplate mongo;

    LegacyIndexCleanup(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void dropLegacyIndexes() {
        LEGACY.forEach((collection, index) -> {
            var operations = mongo.indexOps(collection);
            if (operations.getIndexInfo().stream()
                    .anyMatch(info -> info.getName().equals(index))) operations.dropIndex(index);
        });
    }
}
