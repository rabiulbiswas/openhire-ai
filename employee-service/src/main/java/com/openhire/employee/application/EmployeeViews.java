package com.openhire.employee.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.openhire.employee.domain.EmployeeTypes.Contact;
import java.util.List;

/** Read models returned by the API; field names match the console's TypeScript contracts. */
public final class EmployeeViews {
    private EmployeeViews() {}

    public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {
        public static <T> Page<T> of(List<T> data) {
            return new Page<>(data, null, false);
        }
    }

    public record EmployeeSummary(
            String id,
            String name,
            String employeeCode,
            String role,
            String joiningDate,
            int onboardingPct,
            String hrSyncStatus) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmployeeDetail(
            String id,
            String name,
            String employeeCode,
            String role,
            String joiningDate,
            int onboardingPct,
            String hrSyncStatus,
            String status,
            String department,
            String designation,
            String location,
            String managerName,
            String offerId,
            String applicationId,
            String sagaStatus) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskView(String id, String label, boolean done, String group, String doneAt) {}

    public record OnboardingView(String employeeId, List<TaskView> tasks) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyOnboardingView(
            String employeeId,
            String employeeNo,
            String joiningDate,
            String name,
            Contact buddy,
            Contact hrContact,
            List<TaskView> tasks) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SagaView(
            String id,
            String offerId,
            String applicationId,
            String candidateName,
            String status,
            String step,
            String lastError,
            String updatedAt,
            boolean stuck,
            int attempts,
            String hrisStatus,
            String employeeNo,
            String employeeId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RewardView(
            String id,
            String applicationId,
            String status,
            String eligibleAt,
            String jobTitle,
            String candidateName,
            String joiningDate) {}
}
