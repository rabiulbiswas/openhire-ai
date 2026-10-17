package com.openhire.employee.domain;

import com.openhire.employee.domain.EmployeeTypes.RewardStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A referrer's reward for a hire: pending until the employee is active and past the probation window. */
@Document("referral_rewards")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_referrer", def = "{'tenantId': 1, 'referrerId': 1}"),
    @CompoundIndex(name = "reward_sweep", def = "{'status': 1}")
})
public class ReferralReward {
    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String referrerId;
    private String employeeId;
    private String applicationId;
    private String offerId;
    private String jobTitle;
    private String candidateName;
    private String joiningDate;
    private RewardStatus status;
    private Instant createdAt;
    private Instant eligibleAt;

    protected ReferralReward() {}

    public static String idFor(String tenantId, String offerId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "\u0000reward\u0000" + offerId).getBytes(StandardCharsets.UTF_8));
            return "rr_" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ReferralReward pending(Employee employee, String referrerId, Instant now) {
        var reward = new ReferralReward();
        reward.id = idFor(employee.tenantId(), employee.offerId());
        reward.tenantId = employee.tenantId();
        reward.referrerId = referrerId;
        reward.employeeId = employee.id();
        reward.applicationId = employee.applicationId();
        reward.offerId = employee.offerId();
        reward.jobTitle = employee.jobTitle();
        reward.candidateName = employee.name();
        reward.joiningDate = employee.joiningDate();
        reward.status = RewardStatus.PENDING_PROBATION;
        reward.createdAt = now;
        return reward;
    }

    /** Eligible once the employee is active and {@code days} have passed since the joining date. */
    public boolean dueFor(Employee employee, LocalDate today, int days) {
        return status == RewardStatus.PENDING_PROBATION
                && employee.status() == EmployeeTypes.EmployeeStatus.ACTIVE
                && !today.isBefore(LocalDate.parse(joiningDate).plusDays(days));
    }

    public void markEligible(Instant now) {
        if (status == RewardStatus.ELIGIBLE) return;
        status = RewardStatus.ELIGIBLE;
        eligibleAt = now;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String referrerId() {
        return referrerId;
    }

    public String employeeId() {
        return employeeId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String jobTitle() {
        return jobTitle;
    }

    public String candidateName() {
        return candidateName;
    }

    public String joiningDate() {
        return joiningDate;
    }

    public RewardStatus status() {
        return status;
    }

    public Instant eligibleAt() {
        return eligibleAt;
    }
}
