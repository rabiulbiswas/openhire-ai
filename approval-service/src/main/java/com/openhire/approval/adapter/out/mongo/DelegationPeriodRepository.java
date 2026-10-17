package com.openhire.approval.adapter.out.mongo;

import com.openhire.approval.domain.DelegationPeriod;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DelegationPeriodRepository extends MongoRepository<DelegationPeriod, String> {
    List<DelegationPeriod> findByTenantIdAndOwnerIdOrderByStartsAtDesc(String tenantId, String ownerId);

    Optional<DelegationPeriod>
            findFirstByTenantIdAndOwnerIdAndStartsAtLessThanEqualAndEndsAtGreaterThanEqualOrderByStartsAtDesc(
                    String tenantId, String ownerId, Instant startsAt, Instant endsAt);
}
