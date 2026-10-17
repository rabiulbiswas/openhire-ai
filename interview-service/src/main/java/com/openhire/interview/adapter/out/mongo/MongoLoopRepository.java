package com.openhire.interview.adapter.out.mongo;

import com.openhire.interview.application.port.InterviewPorts.LoopRepository;
import com.openhire.interview.domain.InterviewLoop;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoLoopRepository implements LoopRepository {
    private final MongoTemplate mongo;

    MongoLoopRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public InterviewLoop save(InterviewLoop loop) {
        return mongo.save(loop);
    }

    @Override
    public Optional<InterviewLoop> findByApplication(String tenantId, String applicationId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .is(applicationId)),
                InterviewLoop.class));
    }

    @Override
    public Optional<InterviewLoop> findByRound(String tenantId, String roundId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("rounds.roundId")
                        .is(roundId)),
                InterviewLoop.class));
    }

    @Override
    public List<InterviewLoop> findWithRoundsStartingBetween(String tenantId, Instant from, Instant to) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("rounds.slot.start")
                        .gte(from)
                        .lt(to)),
                InterviewLoop.class);
    }

    @Override
    public List<InterviewLoop> findWithUnscheduledRounds(String tenantId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("closed")
                                .is(false)
                                .and("rounds.status")
                                .in("UNSCHEDULED", "NO_SHOW"))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(200),
                InterviewLoop.class);
    }

    @Override
    public List<InterviewLoop> findForInterviewer(String tenantId, String email, String name) {
        var matches = new java.util.ArrayList<Criteria>();
        if (email != null && !email.isBlank())
            matches.add(Criteria.where("rounds.panel.email").is(email.toLowerCase(java.util.Locale.ROOT)));
        if (name != null && !name.isBlank())
            matches.add(Criteria.where("rounds.panel.name").regex("^" + Pattern.quote(name.trim()) + "$", "i"));
        if (matches.isEmpty()) return List.of();
        return mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId).orOperator(matches.toArray(Criteria[]::new)))
                        .limit(200),
                InterviewLoop.class);
    }

    @Override
    public List<InterviewLoop> findNeedingSweep(Instant now, int limit) {
        var ended = Criteria.where("rounds")
                .elemMatch(
                        Criteria.where("status").is("SCHEDULED").and("slot.end").lte(now));
        var late = Criteria.where("rounds")
                .elemMatch(Criteria.where("status")
                        .is("COMPLETED")
                        .and("feedbackDueAt")
                        .lte(now)
                        .and("feedbackComplete")
                        .is(false));
        return mongo.find(Query.query(new Criteria().orOperator(ended, late)).limit(limit), InterviewLoop.class);
    }
}
