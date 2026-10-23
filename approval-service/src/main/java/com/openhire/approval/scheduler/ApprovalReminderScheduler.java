package com.openhire.approval.scheduler;

import com.openhire.approval.service.ApprovalApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApprovalReminderScheduler {
    private final ApprovalApplicationService approvals;

    public ApprovalReminderScheduler(ApprovalApplicationService approvals) {
        this.approvals = approvals;
    }

    @Scheduled(fixedDelayString = "${openhire.approvals.reminder-delay:3600000}")
    void remindOverdueApprovers() {
        approvals.publishDueReminders();
    }
}
