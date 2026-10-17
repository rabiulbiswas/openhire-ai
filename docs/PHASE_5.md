# Phase 5 — offers, hire saga and employee workflows

## Status — 2026-10-15

**Complete and runtime-accepted on the local stack.** The sections up to *Deviations* are the design and contract the
services and UI were built against; *As built* records what differs, and *Evidence* what was run. Owner decisions
still open are listed under *Open items*.

## Services

| Service | Port | Database | Events topic | Responsibility |
|---|---|---|---|---|
| `offer-service` | 8090 (host 8095 under compose) | `oh_offer` | `offer.events.v1` | Templates and clauses, immutable offer versions, approval chain, send / view / negotiate / accept / decline / expire / rescind |
| `employee-service` | 8091 (host 8096 under compose) | `oh_employee` | `employee.events.v1` | Hire saga, employee records and numbers, onboarding checklists, HRIS sync request, referral rewards |

Both follow the Phase 4 service layout (hexagonal packages, `service-support` security and embedded outbox
relay, Mongo, Kafka with `@RetryableTopic`, ArchUnit test). Gateway routes, compose entries (profile `phase5`),
the launcher (`./run-backend.sh phase5`) and BFF permissions are already wired.

Shared conventions: tenant from the JWT, internal calls with `X-Internal-Service-Secret`, events use the
Phase 4 envelope (`eventId, producer, eventType, eventVersion=1, occurredAt, tenantId, aggregateType,
aggregateId, correlationId=applicationId, payload`) and are stored on the aggregate as `_events` for the
embedded outbox relay.

## Offer lifecycle

`DRAFT → PENDING_APPROVAL → APPROVED → SENT → VIEWED → NEGOTIATING → ACCEPTED | DECLINED | EXPIRED | RESCINDED`

- An offer is created from an application whose stage is `OFFER` (a recorded HIRE decision). One open offer per
  application; creating again returns it.
- **Versions are immutable.** Every terms change appends an `OfferVersion` (`versionNo`, template and version,
  terms, cover note, rendered letter text, `sha256`). Sending freezes the version that was sent.
- **Compensation validation:** `base > 0`, `variable, bonus >= 0`, currency equals the job band currency, base
  inside `[band.min, band.max]` (otherwise 400). `withinBand` is returned for the UI.
- **Approval chain (rules):** step 1 `HIRING_MANAGER`, step 2 `HR_ADMIN`, plus step 3 `FINANCE` when
  `base > (band.min + band.max) / 2`. Steps are sequential. A step is approved by a user holding its role
  (`FINANCE` is satisfied by `ORG_ADMIN`; `SUPER_ADMIN` satisfies any step). The requester cannot approve, and a
  person cannot approve two steps of the same offer. Rejecting returns the offer to `DRAFT` with a note.
- **Re-approval policy:** a compensation change (base, variable, bonus) after approval returns to
  `PENDING_APPROVAL` with a fresh chain; joining date, location, designation, reporting line and cover note do
  not.
- **Send** requires `APPROVED`, is idempotent (a second call returns the same result and emits nothing), sets
  `expiresAt = terms.expiryDate`, issues an HMAC token and emits `offer.sent.v1` with the candidate link
  `{portal}/offer#token=…`.
- **Candidate** responds through the public API: view (first view → `VIEWED`), accept (typed full name as
  acknowledgement), decline (optional reason), request changes (`NEGOTIATING`, message). Responses are idempotent
  and only valid while the offer is open and not expired.
- **Negotiation:** staff edit terms (new version), re-approval if compensation changed, then send again; the
  candidate sees the latest sent version.
- **Expiry:** a sweep (Redis lock) moves `SENT/VIEWED/NEGOTIATING` offers past `expiresAt` to `EXPIRED`.
- **Rescind** from any open state; also automatic when the application is withdrawn or rejected.

## Offer API (gateway prefix `/api/v1`)

Staff (JWT): permissions `offer:create`, `offer:send`, `offer:approve`, `offer:rescind` (see BFF map).

| Method and path | Purpose |
|---|---|
| `GET /offers?status=` | `Page<OfferSummary>` |
| `GET /offers/{id}` | `OfferDetail` |
| `POST /applications/{applicationId}/offer` | Create draft (body: optional `templateKey`, `terms`, `coverNote`); returns `OfferDetail` |
| `GET /applications/{applicationId}/offer` | The application's current offer, 404 if none |
| `PATCH /offers/{id}` | Body `{terms?, coverNote?}`; appends a version; returns `OfferDetail` |
| `POST /offers/{id}/submit` | Request approval |
| `POST /offers/{id}/approvals/{stepId}/approve` | Approve the current step |
| `POST /offers/{id}/approvals/{stepId}/reject` | Body `{reason}` (required) |
| `POST /offers/{id}/send` | Send to the candidate |
| `POST /offers/{id}/rescind` | Body `{reason?}` |
| `GET /offer-templates`, `PUT /offer-templates/{key}` | Versioned templates (`offer:create`; admins) |
| `GET /offer-clauses`, `PUT /offer-clauses/{key}` | Reusable clause blocks |

```
OfferSummary { id, applicationId, candidateId, candidateName, jobId, jobTitle, status, detail, version }
OfferDetail  = OfferSummary + {
  approvalSteps: [{ id, approver, role, status: DONE|CURRENT|PENDING, at?, decidedBy?, comment? }],
  approvalNote?, templateName?, coverNote,
  terms: { currency, base, variable, bonus, joiningDate, joiningDateNote?, reportsTo, location,
           expiryDate, withinBand, designation },
  band: { currency, min, max }, expiresAt?, sentAt?, respondedAt?, candidateNote?,
  versions: [{ versionNo, createdAt, createdBy, sha256, sent }] }
```

`approver` is the role label (for example `Hiring manager`); `detail` is a short human line such as
`Pending Finance approval`.

Candidate (public, no session):

| Method and path | Auth | Purpose |
|---|---|---|
| `GET /public/offers` | `X-Offer-Token` | `PublicOfferView` (marks VIEWED once) |
| `POST /public/offers/accept` | token | Body `{fullName}`; must match the candidate name case-insensitively |
| `POST /public/offers/decline` | token | Body `{reason?}` |
| `POST /public/offers/negotiate` | token | Body `{message}` |
| `GET /public/applications/{applicationId}/offer?tenantId=` | `X-Application-Receipt` | `{status, offerUrl?}`, where `offerUrl` carries a fresh token; `status` is `NONE` when there is no sent offer |

```
PublicOfferView { jobTitle, companyName, candidateName, status, terms{currency, base, variable, bonus,
  joiningDate, location, designation, reportsTo, expiryDate}, coverNote, letterText, expiresAt, respondedAt?,
  canRespond, revising }
```

## Offer events (`offer.events.v1`, aggregate `Offer`, key `offerId`)

Common payload: `offerId, applicationId, candidateId, jobId, jobTitle, candidateName, versionNo, status, ownerId`.

`offer.created.v1`, `offer.approval.requested.v1` (+ `stepId, currentApprovers:[role], title`),
`offer.approved.v1`, `offer.sent.v1` (+ `offerUrl, expiresAt`), `offer.viewed.v1`,
`offer.negotiation.requested.v1` (+ `message`), `offer.revised.v1` (+ `reapprovalRequired`),
`offer.accepted.v1` (+ `terms{base,currency,variable,bonus,joiningDate,location,designation,reportsTo}`,
`acceptedAt, acceptedBy`), `offer.declined.v1` (+ `reason`), `offer.expired.v1`, `offer.rescinded.v1` (+ `reason`).

## Hire saga (`employee-service`)

Triggered by `offer.accepted.v1`; saga id `HIRE-{offerId}` (idempotent). A persisted saga document is advanced by a
worker, so a restart resumes from the stored step.

| Step | Action | Failure handling |
|---|---|---|
| `SNAPSHOT` | Fetch candidate name, email and phone from application-service (`/internal/applications/{id}/projection`) | Retry with back-off; parked and flagged when exhausted |
| `CREATE_EMPLOYEE` | Allocate `EMP-{yyyy}-{seq:5}` (atomic counter per tenant and year), create `PRE_BOARDING` employee, emit `employee.created.v1` | Never rolled back |
| `UPDATE_APPLICATIONS` | application-service consumes `employee.created.v1`: the application becomes `HIRED` and other active applications of the candidate are closed per policy; step completes when `ApplicationStageChanged → HIRED` is seen | Waits; stuck after 24 h |
| `ONBOARDING` | Create the checklist from the default template | Manual HR task |
| `HRIS_SYNC` | Emit `employee.hris-sync.requested.v1`; completes on `integration.hris.synced.v1`, retries on `integration.hris.failed.v1` (1 m, 10 m, 1 h) then `COMPENSATE_PENDING` with an HR Ops task | integration-hub arrives in Phase 8, so this step is *optional* and does not block `COMPLETED` |
| `COMPLETE` | Saga `COMPLETED` when the mandatory steps are done | Saga list shows stuck sagas |

Employee data minimisation: the event payloads carry ids, number, joining date and designation only. Personal
data (name, email, phone) is stored in the employee record and is not published.

## Employee API

| Method and path | Purpose |
|---|---|
| `GET /employees?status=&joiningFrom=` | `Page<EmployeeSummary>` |
| `GET /employees/{id}` | `EmployeeDetail` |
| `GET /employees/{id}/onboarding` | `{ employeeId, tasks:[{ id, label, done, group, doneAt? }] }` |
| `PATCH /employees/{id}/onboarding/tasks/{taskId}` | Body `{done}` (HR) |
| `GET /me/onboarding` | The signed-in employee's checklist plus `{ employeeNo, joiningDate, name, buddy?, hrContact? }` (matched on the JWT email) |
| `PATCH /me/onboarding/tasks/{taskId}` | Body `{done}` (employee, own tasks only) |
| `GET /hire-sagas?status=` / `POST /hire-sagas/{id}/retry` | Operations view and manual retry (`ORG_ADMIN`, `HR_ADMIN`) |
| `GET /me/referral-rewards` | The signed-in employee's referrals and reward status |

```
EmployeeSummary { id, name, employeeCode, role, joiningDate, onboardingPct,
                  hrSyncStatus: SYNCED | RETRYING | PENDING }
EmployeeDetail  = EmployeeSummary + { status, department?, designation, location, managerName?, offerId,
                  applicationId, sagaStatus }
```

**Referral rewards.** When an application whose `source` is `REFERRAL` and whose `actorId` is the referrer ends in
a hire, a reward record is created `PENDING_PROBATION`; it becomes `ELIGIBLE` after
`referral-reward-days` (default 90) once the employee is `ACTIVE`, and emits `employee.referral-reward.eligible.v1`.

`employee.events.v1` (aggregate `Employee`): `employee.created.v1` (`employeeId, employeeNo, applicationId,
candidateId, jobId, offerId, joiningDate, designation`), `employee.onboarding.completed.v1`,
`employee.hris-sync.requested.v1`, `employee.referral-reward.eligible.v1`.

## Application-service changes

- Projection (`/internal/applications/{id}/projection`) also returns `source`, `actorId`, `owner`.
- Consume `employee.created.v1`: mark the application `HIRED` (only from `OFFER`), close the candidate's other
  active applications as `REJECTED` with reason `HIRED_ELSEWHERE` when `close-other-applications` is true.
- The candidate status view maps `OFFER` and `HIRED` to candidate-friendly wording.

## Communication-service changes

Offer and employee events produce notifications: approvers (role recipients) hear an offer approval is
waiting; the recruiter hears about viewed / negotiation / accepted / declined / expired; the candidate gets an
email for `offer.sent.v1` (template `offer-sent`, with the link) and `offer.accepted.v1` (template
`offer-accepted`); HR hears about `employee.created.v1`.

## Deviations from the plan

- Approval steps have a single role each (the LLD's `ALL | ANY` modes are not needed yet).
- The offer letter is rendered to plain text (`letterText`, shown with preserved line breaks, never as HTML) with a
  SHA-256 checksum, not PDF with object storage; PDF rendering and
  e-sign providers arrive with Phase 8 integrations. Acceptance is a click-to-accept with a typed name.
- HRIS sync is a request event until integration-hub exists (Phase 8).

## As built

- **Approval steps** carry `role` (a code: `HIRING_MANAGER`, `HR_ADMIN`, `FINANCE`) and `approver` (the label shown:
  Hiring manager, HR head, Finance). The console enables an approve button only when the signed-in role can take the
  current step; otherwise it says who the offer is waiting for. `decidedBy` and each version's `createdBy` are
  display names, not account ids.
- **Submitting** an offer also requires a reporting line and a work location (the job has no hiring-manager field
  yet, so the recruiter types it), enforced by the server as well as the form.
- **Roles.** `HR_ADMIN` is a new realm role (added to the realm export; an already-imported realm needs it created,
  which the acceptance script and live spec do). HR admins may also create and send offers, as recruiters can.
- **Hire saga.** Persisted in `hire_sagas` (`HIRE-{offerId}`), advanced by a worker with a Mongo lease so a restart
  resumes at the stored step. The employee number is allocated once per saga and stored before the employee is
  written, so a replay reuses it; the year is the creation year. `UPDATE_APPLICATIONS` waits for the
  `ApplicationStageChanged → HIRED` event and also polls the application every 30 s in case it was missed.
  `COMPLETED` does not wait for HRIS; an HRIS failure retries after 1 m, 10 m and 1 h and then shows
  `COMPENSATE_PENDING` (listed as `Pending` in the console) while the saga stays `COMPLETED`.
- **Activation.** A 60 s sweep activates `PRE_BOARDING` employees whose joining date has arrived; HR can also
  activate one (`POST /employees/{id}/activate`, 409 when already active).
- **Referral rewards** are created `PENDING_PROBATION` for the referrer (the application's `actorId`) when the
  application's source is `REFERRAL`, and become `ELIGIBLE` once the employee is `ACTIVE` and 90 days past joining.
  The eligibility event is written before the reward is saved, so a crash can replay it; consumers dedupe on
  `eventId`.
- **Notifications.** Offer events notify approvers by role (Finance goes to org admins), the recruiter and HR on
  accept / decline / expiry, and the candidate gets `offer-sent` and `offer-accepted` emails. HR hears about each new
  hire. Templates added after a tenant was first seeded now reach that tenant too (the seed guard checks the last
  template, not the first).
- **Mongo and Kafka.** No `@RetryableTopic` on the new consumers: like the Phase 4 services they use the shared
  `WorkflowKafkaErrors` handler (retry, then dead-letter).
- **Ports.** Under compose the offer-service publishes on host port 8095 and the employee-service on 8096
  (8090 and 8091 are commonly taken by other local tools); containers still use 8090 and 8091.
- **Memory.** The stack no longer fit Docker Desktop's default 8 GB (ClamAV was repeatedly OOM-killed), so compose now
  caps Kafka's heap and gives every service a `mem_limit`. Headroom with ClamAV loaded is about 1.4 GB. Raising
  Docker's memory to 12 GB is still advisable.

## Open items

- **HRIS and e-sign.** The HRIS step is a request event until integration-hub exists (Phase 8); the offer letter is
  text with a checksum, not a PDF with an e-sign provider.
- **Job hiring manager.** Jobs have no hiring-manager field, so offers default "Reports to" to blank and job
  approvals still route to the requester's role. A job field (Phase 6 or earlier) would let both default correctly.
- **Approval modes.** Each step has one role; the LLD's `ALL | ANY` modes are not needed yet.
- **Expiry reminders.** Offers expire on schedule, but there is no "expiring soon" reminder to the candidate or recruiter.
- **Hire-saga dashboard.** The API lists sagas and can retry a parked one; the console has hooks but no screen yet.
- **Operations.** Raise Docker Desktop's memory; the portal `/offer` page has a real-browser check but no unit tests
  (the candidate app has no test runner).

## Evidence

All services were rebuilt from current source and recreated before these runs.

| Check | Result |
|---|---|
| `./gradlew test --rerun` | Pass: 281 tests, 0 failures, 16 opt-in tests skipped (offer-service 55, employee-service 51 + 2 opt-in, application-service 25, communication-service 34 + 3 opt-in) |
| Offer state-machine tests | Pass, including a randomized walk (300 seeds x 40 steps) that asserts only legal transitions and that every one of the ten states is reached |
| `verify-phase5-workflow.mjs` | Pass: no offer before a hire decision; band-validated draft; three-step approval (Finance above the midpoint) with role gating, one person per step, requester excluded, notifications; single send; candidate view, negotiate, revise without re-approval, resend; exact-name acceptance, idempotent; hire saga (employee number, application HIRED, other application closed, 13 onboarding tasks, HRIS requested); onboarding for the new hire and HR; activation once; rescind and restart; withdrawal rescinds; portal receipt status; referral reward |
| `OPENHIRE_TEST_RESTART=true verify-phase5-workflow.mjs` | Pass: employee-service stopped before the acceptance, the event waited in Kafka, the saga completed once after restart with a unique employee number |
| `e2e-live/phase5-workflow.spec.ts` | Pass, repeated: real console creates the offer from a hire decision, edits and submits it; a hiring manager and an HR admin approve in their own sessions; the recruiter sends; the candidate accepts in the real portal; HR sees the new hire, works the checklist and activates |
| Acceptance scripts for Phases 1 to 4, and live specs for Phases 1 to 4 | Pass after the changes (the Phase 4 spec now books last week, with a random quarter hour, so reruns do not collide) |
| Frontend | typecheck, lint, tests (console 86) and the candidate build (`/offer`, `/schedule`) pass |

Fixes made while accepting:

- A hiring manager saw an "Approve" button for the HR step; the console now offers it only to a role that can take the
  step. Mock approval steps now use the same role-code / label contract as the server.
- Approvals and version history showed Keycloak account ids; they now show names.
- The server accepted a blank reporting line at submission; it now refuses it.
- The onboarding checklist stayed empty when HR opened a new hire before the saga had built it; it now refreshes while
  empty, and the employee's own onboarding page looks a few times before showing its empty state.
- "Respond by" in the portal was a day late in some time zones; bare calendar dates are now shown as that day everywhere.
- `pathFor` appends non-path parameters as a query string, so notifications open the right approval or offer.
- ClamAV was repeatedly OOM-killed mid-run as the stack grew; see *Memory* above.
