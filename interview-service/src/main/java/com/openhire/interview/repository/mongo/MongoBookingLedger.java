package com.openhire.interview.repository.mongo;

import com.openhire.interview.domain.SlotFinder;
import com.openhire.interview.domain.SlotFinder.Interval;
import com.openhire.interview.service.port.InterviewPorts.BookingConflict;
import com.openhire.interview.service.port.InterviewPorts.BookingLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Each booking reserves one document per interviewer per 15-minute block. The document id is
 * tenant + interviewer + block start, so two overlapping bookings collide on the primary key
 * without needing multi-document transactions on a standalone MongoDB.
 */
@Component
class MongoBookingLedger implements BookingLedger {
    private static final String COLLECTION = "panel_blocks";
    private final MongoTemplate mongo;

    MongoBookingLedger(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.indexOps(COLLECTION)
                .createIndex(new Index()
                        .on("tenantId", Sort.Direction.ASC)
                        .on("interviewerKey", Sort.Direction.ASC)
                        .on("blockStart", Sort.Direction.ASC));
        mongo.indexOps(COLLECTION)
                .createIndex(new Index().on("tenantId", Sort.Direction.ASC).on("roundId", Sort.Direction.ASC));
    }

    @Override
    public void reserve(
            String tenantId, String roundId, Collection<String> interviewerKeys, Instant start, Instant end) {
        var inserted = new ArrayList<String>();
        for (var key : interviewerKeys) {
            for (var block : SlotFinder.blocks(start, end)) {
                var id = id(tenantId, key, block);
                try {
                    mongo.insert(
                            new Document("_id", id)
                                    .append("tenantId", tenantId)
                                    .append("interviewerKey", key)
                                    .append("blockStart", Date.from(block))
                                    .append("roundId", roundId),
                            COLLECTION);
                    inserted.add(id);
                } catch (DuplicateKeyException taken) {
                    var existing = mongo.findById(id, Document.class, COLLECTION);
                    if (existing != null && roundId.equals(existing.getString("roundId"))) continue;
                    if (!inserted.isEmpty())
                        mongo.remove(Query.query(Criteria.where("_id").in(inserted)), COLLECTION);
                    throw new BookingConflict(display(key) + " is already booked at that time");
                }
            }
        }
    }

    @Override
    public void release(String tenantId, String roundId) {
        mongo.remove(
                Query.query(
                        Criteria.where("tenantId").is(tenantId).and("roundId").is(roundId)),
                COLLECTION);
    }

    @Override
    public void retain(
            String tenantId, String roundId, Collection<String> interviewerKeys, Instant start, Instant end) {
        var keep = new HashSet<String>();
        for (var key : interviewerKeys)
            for (var block : SlotFinder.blocks(start, end)) keep.add(id(tenantId, key, block));
        mongo.remove(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("roundId")
                        .is(roundId)
                        .and("_id")
                        .nin(keep)),
                COLLECTION);
    }

    @Override
    public List<Interval> busy(String tenantId, Collection<String> interviewerKeys, Instant from, Instant to) {
        if (interviewerKeys.isEmpty()) return List.of();
        return mongo
                .find(
                        Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("interviewerKey")
                                .in(interviewerKeys)
                                .and("blockStart")
                                .gte(Date.from(from.minus(SlotFinder.BLOCK)))
                                .lt(Date.from(to))),
                        Document.class,
                        COLLECTION)
                .stream()
                .map(row -> {
                    var start = row.getDate("blockStart").toInstant();
                    return new Interval(start, start.plus(SlotFinder.BLOCK));
                })
                .toList();
    }

    private static String id(String tenantId, String key, Instant block) {
        return tenantId + "|" + key + "|" + block.getEpochSecond();
    }

    private static String display(String key) {
        int separator = key.indexOf(':');
        return separator < 0 ? key : key.substring(separator + 1);
    }
}
