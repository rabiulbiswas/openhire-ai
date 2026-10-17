# Phase 7 — compliance, analytics and platform operations

## Status — 2026-10-15

Implementation and acceptance work in progress. The two services, UI integrations and workflow checks exist;
local service tests pass, but the complete live acceptance gate has not passed. Do not mark Phase 7 complete
or advance to Phase 8 on the strength of unit tests alone.

## Goals (from the plan's review gate)

1. **Trace** one candidate decision from the UI action through its AI evidence, model and audit records.
2. **Privacy:** export a candidate's data and erase it across every service that holds it, with evidence.
3. **Governance in view:** fairness output, an SLO dashboard, and dead-letter replay with safeguards.

## Services

| Service | Container port (host under compose) | Database | Topics | Responsibility |
|---|---|---|---|---|
| `compliance-service` | 8095 (8105) | `oh_compliance` | `compliance.events.v1` | Audit ledger with hash chain, consent texts and records, privacy requests (export and erasure process manager), legal holds, retention engine, AI decision history and decision trace, fairness reports and self-identification |
| `analytics-service` | 8096 (8106) | `oh_analytics` | `analytics.events.v1` | CQRS projections of every event family, analytics APIs, platform SLOs, service health, consumer lag, dead-letter inspection and replay |

Both follow the Phase 4 to 6 layout (hexagonal packages, `service-support` security, Mongo, Kafka, ArchUnit
`ArchitectureTest`, in-memory ports in unit tests, opt-in Mongo tests behind `OPENHIRE_RUN_MONGO_TESTS`). Compose
entries (profile `phase7`), gateway routes, the launcher (`./run-backend.sh phase7`) and the BFF permission are wired.
Deviations kept small: the super-admin tenant operations API already exists in identity-service (Phase 1), so Phase 7
wires the console to it and audits it rather than moving it. An optional 192 MB Prometheus profile now stores
low-cardinality SLO gauges and backs API reads with fresh samples; local fallback is explicitly labelled. Source
measurements still derive from event timestamps and polled health/actuator metrics (not histogram percentiles).
Analytics projections live in MongoDB (ClickHouse when volume justifies it).

## Shared libraries (already built)

- **Audit recorder** (`platform-starter`, `com.openhire.platform.audit`): `AuditRecorder.record(AuditRecord)`. A record is
  written to an `audit_outbox` collection in the calling service's own database and relayed to `audit.events.v1` by a
  background thread (at least once, survives a Kafka outage, never throws into the caller). Build records with
  `AuditRecord.of(tenant, "OFFER_SENT").user(subject, name).resource("Offer", id).about(candidateId, applicationId).detail("versionNo", 2).build()`.
  Actions are UPPER_SNAKE codes. `details` are reason codes and counts only: keys that look like names, contact
  details or free text are dropped. Envelope `eventType` is `audit.event.recorded.v1`; payload
  `{ action, actor: { type: USER|CANDIDATE|SYSTEM|AI, id, name? }, resource: { type, id }, candidateId?, applicationId?, details, at }`.
  Every service gets the bean through component scanning; inject it and call it after the action succeeds.
- **Privacy participant** (`service-support`, `com.openhire.support.privacy`): a service that holds candidate personal data
  provides one bean implementing `PrivacyParticipant { service(); export(tenant, candidateId); erase(tenant, requestId, candidateId) }`.
  The framework consumes `compliance.erasure.requested.v1` and `compliance.export.requested.v1` from
  `compliance.events.v1` and replies on `privacy.acks.v1` (`privacy.erasure.completed.v1` with `{ requestId, service,
  candidateId, status: ERASED|NOTHING_HELD|RETAINED_LEGAL_BASIS, counts, note? }`, `privacy.export.completed.v1` with
  `{ requestId, service, candidateId, status: EXPORTED, section }`). `Tombstones.mark/isErased` (collection
  `erased_candidates`) lets event consumers skip an erased candidate so late events cannot resurrect data.

## Audit ledger (compliance-service)

- Consumes `audit.events.v1`, deduplicating on `eventId`, and appends `audit_events`:
  `{ _id: aud_<ulid>, tenantId, seq, action, actor, resource, candidateId?, applicationId?, details, source, at, prevHash, hash }`.
  `seq` is allocated atomically per tenant; `hash = SHA-256(prevHash + canonical JSON of the record without hash)`;
  the first record's `prevHash` is 64 zeros. Indexes: `(tenantId, seq)` unique, `(tenantId, resource.id, at)`,
  `(tenantId, action, at)`, `(tenantId, candidateId, at)`.
- `GET /audit-events?actor=&action=&resourceType=&resourceId=&candidateId=&from=&to=&limit=&cursor=` returns a page
  `{ data, nextCursor, hasMore }` newest first.
- `GET /audit-events/verify` recomputes the chain per tenant and returns `{ valid, checked, brokenAtSeq? }`.
- `GET /audit-events/export?from=&to=` streams NDJSON and records a `DATA_EXPORT` audit event.
- Roles: `COMPLIANCE_OFFICER`, `ORG_ADMIN`, `SUPER_ADMIN`.
- **Completeness.** These actions must produce an audit record (emitted by the owning service): job created, published,
  closed; approval approved, rejected, delegated; application shortlisted, rejected, stage changed by a person, decision
  undone, eligibility reviewed, withdrawn by the candidate, imported; assessment overridden (with `reasonCode`); hire or
  no-hire decision recorded; candidate merged; interview scheduled, cancelled, scorecard submitted; offer created,
  submitted, approved, rejected, sent, rescinded, accepted, declined; template changed; message sent by staff; talent
  consent granted or revoked, talent invitation sent; employee activated, hire saga retried; integrity signal dismissed;
  prompt promoted, AI settings changed, evaluation run; role changed, user removed, API key created, rotated or revoked,
  SSO config changed, organization created or status changed, break-glass granted; processing consent given with its
  version; privacy request created, completed; legal hold placed, released; retention run; consent text published;
  dead letter replayed; data exported.

## Decision trace and AI decision history

compliance-service also consumes `application.events.v1`, `assessment.events.v1`, `integrity.events.v1`,
`offer.events.v1` and `employee.events.v1` into `subject_activity` (per candidate: applications, jobs, stage timeline,
last activity, lifecycle) and `ai_decisions`.

- `GET /ai-decisions?candidateId=&applicationId=&task=&from=&limit=` returns
  `[{ id, applicationId, candidateId, candidateName?, task: "MATCH_ASSESSMENT", at, model, promptVersion, rubricVersion,
  outcome: { status, score, confidence }, humanAction?: { action, by, at, reason?, reasonCode? } }]`. `candidateName` is
  resolved at query time from application-service's projection (never stored here).
- `GET /ai-decisions/{id}` returns the assessment criteria with exact evidence quotes and grounding flag, the integrity
  flags, and the human actions that followed.
- `GET /trace/applications/{applicationId}` returns one ordered timeline `[{ at, kind: AUDIT|AI|STAGE|OFFER|PRIVACY, summary,
  actor?, ref }]` joining the audit records, the AI decision (model, prompt, rubric versions) and the stage and offer
  events for that application, so a reviewer can follow a rejection or hire from the UI click to the evidence.

## Consent

- `GET /consent-texts` lists versions `{ id, version, purpose, locale, text, status: ACTIVE|RETIRED, publishedBy, publishedAt }`;
  `POST /consent-texts { purpose, locale, text }` publishes a new version (previous one retires; text 20 to 4,000
  chars) and audits `CONSENT_TEXT_PUBLISHED`. `GET /consent-records?candidateId=` lists consents given (from
  `CONSENT_GRANTED` / `CONSENT_REVOKED` audit events and application events): `{ candidateId, purpose, version,
  status, at }`.
- Internal `GET /api/v1/internal/consent/active?tenantId=&purpose=PROCESSING` (service secret) returns the active text and
  version; application-service's `/public/consent` uses it (60 s cache, falls back to its built-in text if
  compliance-service is unreachable) and keeps validating the submitted `consentVersion` against the active one.

## Privacy requests and erasure (process manager)

`privacy_requests`: `{ id, tenantId, candidateId, type: EXPORT|ERASURE, source: CANDIDATE|STAFF|RETENTION, reason?,
status: REQUESTED|BLOCKED_BY_LEGAL_HOLD|IN_PROGRESS|COMPLETED|FAILED, participants: { <service>: { status, counts, note, at } },
createdAt, completedAt?, dueAt (30 days), stuck, evidenceHash? }`.

- Staff: `GET /privacy/requests?status=`, `GET /privacy/requests/{id}`, `POST /privacy/requests { candidateId, type, reason }`
  (COMPLIANCE_OFFICER, ORG_ADMIN).
- Candidate (public, receipt checked through application-service, which also yields the candidate id):
  `POST /public/applications/{applicationId}/privacy-requests?tenantId= { type }`, `GET` the same path for the
  candidate's own requests, and `GET /public/privacy-requests/{id}/export?tenantId=` (header `X-Application-Receipt`)
  returns the assembled JSON once COMPLETED (available `export-ttl-days`, then 410).
- **Export:** emits `compliance.export.requested.v1`; each registered participant answers with its section; when all
  have answered the sections are assembled `{ generatedAt, candidateId, sections: { <service>: {...} } }`.
- **Erasure:** a legal hold on the candidate or their job moves the request to `BLOCKED_BY_LEGAL_HOLD` (it resumes
  when the hold is released); otherwise it emits `compliance.erasure.requested.v1`, waits for every registered
  participant (`openhire.compliance.participants`) to acknowledge, completes when all have (a participant may answer
  `RETAINED_LEGAL_BASIS` with a reason, for example an employee record), flags `stuck` after `stuck-after-hours` and
  produces the evidence report `{ requestId, candidateIdHash, perService: [{ service, status, counts, note, at }],
  completedAt, sha256 }`. After completion compliance-service keeps only a tombstone (candidate id hash, request id)
  and its own audit records. Duplicate acknowledgements are ignored. Emits `compliance.erasure.completed.v1`.
- Participants (each service answers for its own data): application-service, candidate-service, resume-processor,
  communication-service, notification-dispatcher, interview-service, offer-service, employee-service (an employee's
  record is `RETAINED_LEGAL_BASIS`, applicant-stage data is erased), talent-service, ai-workers.

## Legal holds and retention

- `POST /legal-holds { candidateId? , jobId?, reason, expiresAt? }`, `GET /legal-holds?status=`,
  `POST /legal-holds/{id}/release { reason }`. An active hold blocks erasure and retention actions for the candidate
  (or every candidate who applied to the job). Emits `compliance.legal-hold.placed.v1` / `released.v1`.
- `GET /retention-policies` returns `[{ key, label, description, retentionMonths, action: ERASE|MINIMIZE|ARCHIVE,
  enabled, lastRunAt?, lastRunSummary? }]` with defaults `candidate.non-hired` 24 months after last activity (ERASE),
  `candidate.withdrawn` 6 months (ERASE), `audit.events` 84 months (ARCHIVE), `ai.request-logs` 12 months (ERASE).
  `PUT /retention-policies/{key} { retentionMonths, enabled }` (COMPLIANCE_OFFICER, ORG_ADMIN; minimum 1 month, audit
  at least 12). `POST /retention-policies/run?dryRun=true[&asOf=<ISO instant>]` lists who would be affected and which holds protected whom; `asOf` (dry run only, ignored otherwise) evaluates as if that were now, so the rule can be checked without waiting years. The response is `{ dryRun, evaluatedAt, policies: [{ key, wouldErase: [{ candidateId, lastActivityAt }], protectedByHold: [{ candidateId, holdId }], skippedInProgress }] }`.
- A daily run (Redis lock) evaluates `candidate.*` policies over `subject_activity` (not hired, no application in
  progress, last activity older than the period, no active hold) and creates ERASURE requests with source
  RETENTION and the evidence (policy, months, last activity), at most `retention-batch` per run.
- The same run executes `audit.events` and `ai.request-logs` in bounded batches and returns `recordPolicies`.
  Audit archival inserts and verifies copies in `audit_archive`, retaining the original ledger for full chain
  verification. AI-log purge runs inside AI gateway, preserves unpublished outbox records, and is conservatively
  blocked by any active tenant legal hold. Dry runs do not change records. See [operating details](PHASE_7_OPERATIONS.md).

## Fairness

- Candidates may voluntarily give self-identification data: public `PUT /public/applications/{applicationId}/self-identification?tenantId=`
  (receipt) `{ gender?, ethnicity?, ageBand?, disability? }` (each optional, including PREFER_NOT_TO_SAY) and `GET`
  of the same path. Stored AES-GCM encrypted (key `COMPLIANCE_DATA_KEY`) in a separate collection keyed by application,
  never returned to reviewers except as aggregates, and erased with the candidate.
- `GET /reports/fairness?jobId=&dimension=gender|ethnicity|ageBand|disability` returns, for each stage transition
  (APPLIED to SHORTLISTED, SHORTLISTED to INTERVIEWING, INTERVIEWING to OFFER, OFFER to HIRED), per group
  `{ group, n, selected, selectionRate }`, the highest group rate, `adverseImpactRatio = groupRate / highestRate`, and
  `flag: true` below `adverse-impact-threshold` (0.8). Groups with `n < min-group-size` (5) are shown as
  `insufficientData` and never flagged. Snapshots go to `fairness_reports`; a weekly job refreshes them and emits
  `compliance.fairness.alert.v1` for new flags.
- `GET /compliance/overview` returns `{ auditRecords30d, chainValid, openPrivacyRequests, stuckPrivacyRequests,
  activeLegalHolds, fairnessFlags, activeConsentVersion, lastRetentionRun, recentAudit: [..10], recentPrivacy: [..5] }`.

## Analytics (analytics-service)

Consumes `application.events.v1`, `assessment.events.v1`, `integrity.events.v1`, `interview.events.v1`,
`offer.events.v1`, `employee.events.v1`, `job.events.v1`, `resume.events.v1`, `communication.events.v1` /
`communication.delivery.v1`, `ai.events.v1` and `audit.events.v1` without querying any service database, into
materialized views: `fact_application` (job, source, submittedAt, stage timeline, hire and rejection times),
`fact_assessment` (score, confidence, model, completion time), `fact_decision` (human shortlist or reject, override
with reason code), `fact_offer`, `fact_ai_day` (per day, task, model: requests, errors, tokens, cost INR, latency).
All reads are tenant-scoped.

| Endpoint | Response |
|---|---|
| `GET /analytics/kpis?range=30d\|90d\|1y&jobId=` | `AnalyticsKpis`: `medianTimeToHireDays`, `offerAcceptancePct`, `aiShortlistAgreementPct`, `aiCostPerScreenedApplicant`, each with a `…Delta` string against the previous period, and `currency` |
| `GET /analytics/funnel?jobId=&range=` | `{ jobTitle, stages: [{ label, value }] }` (applied, AI assessed, shortlisted, interviewing, offer, hired, counted as reached) |
| `GET /analytics/time-to-hire?range=` | `[{ x: "2026-W41", y: medianDays }]` weekly |
| `GET /analytics/sources?range=` | `[{ source, applicants, shortlistRatePct, hires }]` |
| `GET /analytics/ai-overrides?range=` | `[{ label, value }]` grouped by override reason code (UNDERWEIGHTED_EVIDENCE, OVERWEIGHTED_GAP, CONTEXT_NOT_IN_RESUME, OTHER) |
| `GET /analytics/stage-latency?range=` | `[{ stage, medianHours, p90Hours }]` |

Definitions: time to hire is application submitted to HIRED; offer acceptance is accepted over (accepted + declined +
expired); **AI shortlist agreement** is, among applications where a person shortlisted or rejected after an AI
assessment, the share where the AI recommended the same (score at least 60 means shortlist); AI cost per screened
applicant is AI cost INR over applications assessed (0 for local models). Every number must reconcile with the seeded
journeys the acceptance run creates.

The assessment override API gains an optional `reasonCode` (`UNDERWEIGHTED_EVIDENCE`, `OVERWEIGHTED_GAP`,
`CONTEXT_NOT_IN_RESUME`, `OTHER`; default `OTHER`), stored and published in the override event; the console's adjust-score
dialog offers it.

## Platform operations (analytics-service, super admin and org admin)

- `GET /platform/services` (SUPER_ADMIN): `[{ name, status: UP|DOWN|DEGRADED, uptimePct24h, lastCheckedAt, responseMs,
  requests24h?, errors24h? }]` from polling each service's `/actuator/health` and `/actuator/metrics/http.server.requests`
  every `poll-seconds`.
- `GET /platform/slo` (SUPER_ADMIN, ORG_ADMIN): `[{ id, label, target, window, current, status: MET|AT_RISK|BREACHED,
  errorBudgetRemainingPct, basis }]` for public API availability, application API latency (mean and max, flagged
  `approximate`), resume processing within 60 s, AI assessment within 2 min (from `ApplicationScreened` to the COMPLETE
  `assessment.completed.v1`), and notification hand-off within 60 s (from `communication.message.requested.v1` to a
  delivery update).
- `GET /platform/consumers` (SUPER_ADMIN): `[{ group, topic, lag, status: OK|LAGGING }]` using the Kafka admin client.
- **Dead letters** (`ops:dlq:replay`, org admins see only their tenant's messages, SUPER_ADMIN all):
  `GET /admin/dlq?limit=` returns `[{ id: "<topic>:<partition>:<offset>", originalTopic, consumerGroup, eventType,
  tenantId, error, attempts?, firstFailedAt, replayedAt?, replayedBy?, payloadPreview }]` read from the `*.DLT` topics;
  `GET /admin/dlq/{id}` the full payload. Payloads are redacted: keys that look like names, contact details, text or
  resume content are masked, only ids remain. `POST /admin/dlq/{id}/replay { reason }` (reason required, 5 to 500
  chars) republishes the original value and key to the original topic with a `x-replayed-from` header, **once**
  (a second replay is `409` unless `force=true` by SUPER_ADMIN), at most `dlq-replay-per-minute` per minute per tenant,
  and audits `DEAD_LETTER_REPLAYED` with the reason. `POST /admin/dlq/replay { reason, topic?, limit<=50, dryRun? }`
  replays a batch with the same safeguards; `dryRun=true` only counts.
- Tenant operations (organizations, quota, break-glass with mandatory reason, expiry and notifications) already exist in
  identity-service; the super-admin app is wired to them and they are audited.

## Event topics

`audit.events.v1` (`audit.event.recorded.v1`), `compliance.events.v1` (`compliance.erasure.requested.v1`,
`compliance.export.requested.v1`, `compliance.erasure.completed.v1`, `compliance.export.ready.v1`,
`compliance.legal-hold.placed.v1`, `compliance.legal-hold.released.v1`, `compliance.fairness.alert.v1`),
`privacy.acks.v1`, `analytics.events.v1` (reserved). Contracts: `contracts/phase7-api.openapi.json`,
`contracts/phase7-events.asyncapi.yaml`.

## Evaluation of the plan's verification list

Audit completeness and event-to-decision trace, retention, legal hold and multi-service erasure integration, metric
reconciliation against seeded journeys, and tenant isolation are covered by the acceptance script and unit tests.

## As built

- Both services are registered with the gateway and Compose launcher; local containers were observed healthy.
- REST and event contracts are now present in `backend/contracts/phase7-api.openapi.json` and
  `backend/contracts/phase7-events.asyncapi.yaml`. The REST contract covers 42 operations, including protected
  monitoring and owner-side retention endpoints; flexible response-map fields are detailed in this document.
- Service guides describe business logic, REST endpoints, patterns and components:
  [compliance-service](services/compliance-service.md), [analytics-service](services/analytics-service.md).
- The analytics startup test now injects the real shared `AuditRecorder`, rather than masking wiring failures
  with a mock.
- Privacy acknowledgements now require the matching candidate, request type and IN_PROGRESS state. Unknown or
  failed statuses cannot count as success; retained records require a legal-basis explanation; export acknowledgements
  require a section object. Regression tests verify rejection without poisoning later valid acknowledgements.
- The live workflow now runs a read-only local-only preflight before creating fixtures, checking all required
  Compose dependencies, including the scanner.
- The previously open Prometheus and audit/AI-log retention execution gaps are implemented and independently
  tested. Monitoring is optional, capped at 192 MB, and does not increase Docker's configured allocation. Audit
  archival is logical preservation (not cold storage); tenant-held AI logs and unpublished events are not deleted.
  The console's dry-run preview displays record-policy outcomes. Deployment instructions are in
  [Phase 7 operations](PHASE_7_OPERATIONS.md).

## Open items

- **Runtime gate blocked:** ClamAV's daemon was killed by an out-of-memory event. Docker reports about 8 GB total
  memory for the complete stack. One targeted scanner restart did not restore health. Resolve scanner memory
  pressure (for example by reducing simultaneous development workloads), then confirm health before rerunning acceptance. Malware scanning must
  not be bypassed to obtain a passing result.
- Rebuild/redeploy compliance-service, ai-gateway and analytics-service before live acceptance. The changes have
  not been deployed to the already-running stack during this independent implementation/test pass.
- Rerun `node scripts/testing/verify-phase7-workflow.mjs` and the live Phase 7 browser workflow. Verify decision
  provenance, complete distributed export/erasure, retention and legal holds, fairness, metric reconciliation,
  tenant isolation and dead-letter replay against the running stack.
- Production boundaries remain: Prometheus serves sampled rolling indicators, not a new HTTP histogram system;
  alert delivery needs an external receiver; audit copies are in the same Mongo database, not an independent backup.
  These boundaries are documented rather than represented as production durability/precision guarantees.

## Evidence

Observed on 2026-10-15 (not claims about unexecuted checks):

| Check | Result |
|---|---|
| `OPENHIRE_RUN_MONGO_TESTS=true ./gradlew :analytics-service:test :compliance-service:test :ai-gateway:test --max-workers=1` | Passed: 99 analytics + 75 compliance + 102 AI-gateway tests, zero failures; one separate opt-in OllamaIntegrationTest skipped. New Mongo archival/purge tests executed. |
| `node scripts/testing/verify-phase7-contracts.mjs` | Passed: all 42 REST operations match controllers, unique operation IDs, valid path parameters and resolved local schema references |
| Prometheus configuration and rules | Actual promtool validation passed in temporary 192 MB-capped containers; both alert rules passed synthetic-series rule tests. No full-stack startup required. |
| Console TypeScript check | Passed |
| Console unit suite | Final serial run passed all 158 tests in 32 files (`vitest run --maxWorkers=1`), including the new record-retention preview test |
| Live backend workflow | Role sessions and access boundaries passed; failed waiting for candidate rules screening (`Anika Rao c1181cd2`) while the scanner was unavailable |
| New workflow preflight | Correctly refused to proceed while ClamAV was not healthy; no new fixtures created by this attempt |
| Full live browser workflow | Not rerun after this patch; not accepted |

The initial workflow leaves labelled development job/candidate fixtures (run `c1181cd2`); its temporary Keycloak
user cleanup ran through the script's `finally` block. No existing business records were intentionally erased.
