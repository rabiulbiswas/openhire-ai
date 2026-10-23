package com.openhire.talent.repository.mongo;

import com.openhire.talent.domain.Invitation;
import com.openhire.talent.service.port.TalentPorts.InvitationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoInvitations implements InvitationRepository {
    private final MongoTemplate mongo;

    MongoInvitations(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Invitation save(Invitation invitation) {
        return mongo.save(invitation);
    }

    @Override
    public Optional<Invitation> latestFor(String tenantId, String candidateId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("candidateId")
                                .is(candidateId))
                        .with(Sort.by(Sort.Direction.DESC, "invitedAt")),
                Invitation.class));
    }

    @Override
    public List<Invitation> since(String tenantId, Instant since) {
        return mongo.find(
                Query.query(
                        Criteria.where("tenantId").is(tenantId).and("invitedAt").gte(since)),
                Invitation.class);
    }
}
