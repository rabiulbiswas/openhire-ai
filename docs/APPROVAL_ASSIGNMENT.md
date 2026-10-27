# Named approval assignments and email notifications

Jobs require exactly one active tenant hiring manager before submission. Pass `hiringManagerId` when creating/editing a job, or set one `HIRING_MANAGER` on its hiring team. That person receives new-job and description-change approval requests. The requester cannot be the approver. The assigned manager is frozen while approval is pending.

Recruiters can create and track requests, but have `approval:read`, not approval decision/delegation permissions. Hiring Managers, HR Admins and Organisation Admins may decide only their explicitly assigned current step, with a current active tenant membership holding that step's role. Administrator/platform status is not an assignment override. Decisions are tenant-scoped; stale token roles do not override live membership checks.

Offer submission accepts `{ "assignees": { "HIRING_MANAGER": "user-id", "HR_ADMIN": "user-id", "FINANCE": "user-id" } }`. Finance is required only above salary-band midpoint and is assigned to a named Organisation Admin, not a new Finance login role. Each required step needs a different person, excluding the offer creator. Only required, validated assignments are saved. Compensation revisions introducing a new approval role return to draft for explicit assignment and resubmission.

Older wildcard requests cannot be approved. The requester or Organisation Admin can explicitly repair them using `POST /approvals/{id}/assignment` with `assigneeId`. A legacy Recruiter decision step also requires an eligible replacement `role`. Earlier completed steps are preserved. Older offers without assignments can use `PUT /offers/{id}/approval-assignees`; completed steps must retain their original decision-makers. No account is promoted and no approval decision is fabricated.

Approval requests email the named current approvers. Decisions email the requester and named participants, and advancing a chain emails the next named approver. Offer acceptance/decline notifications go to the requester and explicitly assigned offer participants, not all users with an HR role. Candidate-facing offer emails continue through the existing receipt-protected workflow.

Workflow emails resolve current addresses from identity-service even if a user never opened notification settings. Disabled/removed members receive no email. Approval and offer-response emails are transactional and immediate, separate from optional notification digests/quiet hours. Existing outbox delivery and dispatcher retries apply; event redelivery uses stable recipient deduplication keys. Role-key notifications outside these named approval flows remain in-app only.

Local development routes email through Mailpit. This does not prove delivery to external Gmail/Outlook inboxes; production requires a configured SMTP provider.
