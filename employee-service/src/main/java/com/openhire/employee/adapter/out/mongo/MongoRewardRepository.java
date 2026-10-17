package com.openhire.employee.adapter.out.mongo;

import com.openhire.employee.application.port.EmployeePorts.RewardRepository;
import com.openhire.employee.domain.EmployeeTypes.RewardStatus;
import com.openhire.employee.domain.ReferralReward;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoRewardRepository implements RewardRepository {
    private final MongoTemplate mongo;

    MongoRewardRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void insertIfAbsent(ReferralReward reward) {
        try {
            mongo.insert(reward);
        } catch (DuplicateKeyException exists) {
            // The reward for this hire was already recorded.
        }
    }

    @Override
    public ReferralReward save(ReferralReward reward) {
        return mongo.save(reward);
    }

    @Override
    public List<ReferralReward> findByReferrer(String tenantId, String referrerId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("referrerId")
                                .is(referrerId))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(200),
                ReferralReward.class);
    }

    @Override
    public List<ReferralReward> findPending(int limit) {
        return mongo.find(
                Query.query(Criteria.where("status").is(RewardStatus.PENDING_PROBATION))
                        .limit(limit),
                ReferralReward.class);
    }
}
