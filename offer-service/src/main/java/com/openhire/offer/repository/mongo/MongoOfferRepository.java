package com.openhire.offer.repository.mongo;

import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.domain.OfferTypes.Viewer;
import com.openhire.offer.service.port.OfferPorts.OfferRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoOfferRepository implements OfferRepository {
    private final MongoTemplate mongo;

    MongoOfferRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Offer save(Offer offer) {
        return mongo.save(offer);
    }

    @Override
    public Optional<Offer> find(String tenantId, String offerId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(offerId)), Offer.class));
    }

    @Override
    public Optional<Offer> findByApplication(String tenantId, String applicationId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .is(applicationId)),
                Offer.class));
    }

    @Override
    public List<Offer> list(String tenantId, Status status, int limit) {
        var criteria = Criteria.where("tenantId").is(tenantId);
        if (status != null) criteria = criteria.and("status").is(status);
        return mongo.find(
                Query.query(criteria)
                        .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                        .limit(limit),
                Offer.class);
    }

    @Override
    public List<Offer> findExpired(Instant now, int limit) {
        return mongo.find(
                Query.query(Criteria.where("status")
                                .in(Status.SENT, Status.VIEWED, Status.NEGOTIATING)
                                .and("expiresAt")
                                .lte(now))
                        .limit(limit),
                Offer.class);
    }

    @Override
    public List<Offer> page(
            String tenantId, Status status, String search, Instant at, String id, boolean before, int limit) {
        var filters = new java.util.ArrayList<Criteria>();
        filters.add(Criteria.where("tenantId").is(tenantId));
        if (status != null) filters.add(Criteria.where("status").is(status));
        if (!search.isBlank()) {
            var literal = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(search), java.util.regex.Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria()
                    .orOperator(
                            Criteria.where("candidateName").regex(literal),
                            Criteria.where("jobTitle").regex(literal)));
        }
        if (at != null)
            filters.add(new Criteria()
                    .orOperator(
                            before
                                    ? Criteria.where("createdAt").gt(at)
                                    : Criteria.where("createdAt").lt(at),
                            new Criteria()
                                    .andOperator(
                                            Criteria.where("createdAt").is(at),
                                            before
                                                    ? Criteria.where("_id").gt(id)
                                                    : Criteria.where("_id").lt(id))));
        return mongo.find(
                Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new)))
                        .with(Sort.by(before ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt", "_id"))
                        .limit(limit),
                Offer.class);
    }

    @Override
    public List<Offer> approvalInbox(String tenantId, Viewer viewer, String scope, int limit) {
        var query = approvalQuery(tenantId, viewer, scope)
                .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                .limit(limit);
        query.fields()
                .include(
                        "candidateName",
                        "jobTitle",
                        "ownerId",
                        "createdAt",
                        "status",
                        "approvalSteps",
                        "approvalAssignees");
        return mongo.find(query, Offer.class);
    }

    @Override
    public long countApprovalInbox(String tenantId, Viewer viewer, String scope) {
        return mongo.count(approvalQuery(tenantId, viewer, scope), Offer.class);
    }

    static Query approvalQuery(String tenantId, Viewer viewer, String scope) {
        if ("waiting_on_me".equals(scope)
                && java.util.List.of("HIRING_MANAGER", "HR_ADMIN", "FINANCE").stream()
                        .noneMatch(viewer::canApprove))
            return Query.query(
                    Criteria.where("tenantId").is(tenantId).and("_id").is("__no_assigned_approval__"));
        var tenant = Criteria.where("tenantId").is(tenantId);
        var submitted = new Criteria()
                .orOperator(
                        Criteria.where("approvalSteps.0").exists(true),
                        Criteria.where("approvalNote").exists(true).nin(null, ""));
        Criteria filter =
                switch (scope) {
                    case "waiting_on_me" ->
                        new Criteria()
                                .andOperator(
                                        Criteria.where("status").is(Status.PENDING_APPROVAL),
                                        Criteria.where("ownerId").ne(viewer.subject()),
                                        new Criteria()
                                                .orOperator(
                                                        java.util.List.of("HIRING_MANAGER", "HR_ADMIN", "FINANCE")
                                                                .stream()
                                                                .filter(viewer::canApprove)
                                                                .map(role -> new Criteria()
                                                                        .andOperator(
                                                                                Criteria.where("approvalAssignees."
                                                                                                + role)
                                                                                        .is(viewer.subject()),
                                                                                Criteria.where("approvalSteps")
                                                                                        .elemMatch(Criteria.where(
                                                                                                        "role")
                                                                                                .is(role)
                                                                                                .and("status")
                                                                                                .is("CURRENT"))))
                                                                .toArray(Criteria[]::new)),
                                        Criteria.where("approvalSteps.decidedBy")
                                                .ne(viewer.subject()),
                                        Criteria.where("approvalSteps")
                                                .elemMatch(
                                                        Criteria.where("status")
                                                                .is("CURRENT")
                                                                .and("role")
                                                                .in(java.util.List.of(
                                                                                "HIRING_MANAGER", "HR_ADMIN", "FINANCE")
                                                                        .stream()
                                                                        .filter(viewer::canApprove)
                                                                        .toList())));
                    case "requested_by_me" -> Criteria.where("ownerId").is(viewer.subject());
                    case "completed" ->
                        new Criteria()
                                .andOperator(
                                        Criteria.where("approvalSteps.status").is("DONE"),
                                        Criteria.where("approvalSteps.status").nin("CURRENT", "PENDING"));
                    default -> throw new IllegalArgumentException("Unknown approval scope");
                };
        return Query.query(new Criteria().andOperator(tenant, submitted, filter));
    }
}
