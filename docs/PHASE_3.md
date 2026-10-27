# Phase 3 — application intake, resume processing and Candidate 360

## Embedded extraction change — 2026-10-24

Resume extraction now uses embedded Tika 3.3.3 and native Tesseract inside resume-processor,
not a separate Tika server. Local/CI images and deployment scripts have been updated. Existing
recovery fixtures use an injected extraction port. See [resume processor](services/resume-processor.md)
for resource limits, configuration and local prerequisites. The historical runtime acceptance below
predates this change; a rebuilt-container end-to-end OCR/scanner acceptance run is still required.

## Status — 2026-10-09

**Runtime-accepted.** Every service was rebuilt from current source and force-recreated (including the
CSRF gateway image that was previously built but not deployed), then all acceptance scripts passed:

| Check | Result |
|---|---|
| `verify-phase3-runtime.mjs` | Pass: intake/replay, real scan + extraction, screening, decisions/undo, Candidate 360, withdrawal, CSV import, and new ZIP import (referenced resume + missing-file row) |
| `verify-phase3-workflow.sh` | Pass: career submission, idempotent replay, screening, receipt-protected withdrawal |
| `verify-phase3-resumes.mjs` | Pass: real ClamAV clean/EICAR, PDF/DOCX extraction, corrupt/traversal/macro rejection, Tesseract OCR |
| `verify-phase3-roles.mjs` | Pass: agency assignment/redaction, career/agency identity convergence, employee/referral ownership, tenant isolation, API-key scope (403) and revocation (401) |
| `e2e-live/phase3-workflow.spec.ts` | Pass (5 consecutive runs): real sign-in, add candidate, pipeline, Needs attention, resume recovery, shortlist |
| Kafka broker restart | Pass: consumers reconnected without service restarts, runtime script re-passed, zero consumer lag, no dead-letter topics created |

Fixes made while accepting:

- API-key intake rejected every key with 401 because identity-service was running a superseded build (see
  `PHASE_1.md`). Redeploying current source fixed it.
- Needs attention fetched once and never refreshed, so items that reach the queue after asynchronous
  processing did not appear until reload. `useAttentionQueue` now polls every 15 seconds.
- `verify-phase3-resumes.mjs` hard-coded sharp's old `lib/index.js` entry; it now resolves the entry from
  sharp's `package.json`. The in-memory ZIP builder moved to `phase3-pdf-fixture.mjs` for reuse.
- The candidate portal had no ESLint configuration (`next lint` stopped to prompt). It now uses the shared
  `@openhire/config/eslint` preset like the other apps.

Still open (owner decision): the k6 load test (`phase3-load.js`) has not been run. k6 is not installed, and it
creates about 2,700 applications, so it needs a dedicated development tenant with an OPEN job.

Historical status (2026-10-08) follows.

## Status — 2026-10-08

The Phase 3 implementation now includes all three services and the primary UI workflows, not only the
earlier staff-intake foundation. **The phase is not fully runtime-accepted yet.** Following the owner's
request to fix runtime issues, Java compilation, backend tests, Docker builds and local runtime checks
were performed. The final gateway deployment was blocked by exhausted approval-review workspace credits.

## Implemented scope

| Area | Source implementation |
|---|---|
| Intake | Recruiter, career, agency, employee/internal, referral and scoped API-key channels; normalized source, current consent version, OPEN-job snapshot |
| Replay protection | Tenant/actor/source-scoped idempotency and external correlation, canonical fingerprints, concurrent insert winner |
| Resumes | Quarantine volume, magic bytes, DOCX checks, fail-closed ClamAV adapter, Tika extraction, OCR fallback, bounded workers and retries |
| Identity | Tenant-local normalized-email resolution, field provenance, reviewed name/phone suggestions, merge saga and aliases |
| Processing | Keyword screening, eligibility review, attention/retry/upload recovery, human shortlist/reject/undo/score override |
| Read models | Paginated pipeline, Candidate 360, monotonic event-driven projection with durable HTTP repair |
| Events | Embedded outbox; application, candidate and resume v1 topics; merge reassignment and resume outcome consumers |
| Imports | CSV or ZIP with candidates.csv, per-row consent, durable staging/processing, row failures and polling |
| UI | Recruiter upload/import/pipeline/attention/candidate/duplicates; agency jobs/submissions; employee applications/referrals; public career apply/status/withdraw |
| Contracts | phase3-api.openapi.json and phase3-events.asyncapi.yaml |
| Verification sources | Unit tests, opt-in real-Mongo merge test, career workflow script, sustained/burst k6 test |

## Service boundaries

- application-service: port 8084; oh_application. Commands, source authorization, screening and pipeline.
- candidate-service: port 8085; oh_candidate. Canonical identity, provenance, merge and candidate views.
- resume-processor: port 8086; oh_resume plus resume-data volume. Secure upload and processing.
- service-support is a shared library, **not another service**: security, internal client, indexes and outbox.

The existing local MongoDB server is used. MongoDB Compass is a client for viewing these databases, not
the server. Docker addresses Mongo through host.docker.internal. No Mongo container is introduced.

## Patterns and decisions

Source normalization is a factory-style switch feeding one submission use case. The application lifecycle
is an explicit state machine with revision-conditional writes. Resume handling is a processing pipeline.
Identity suggestions use deterministic specifications followed by reviewer field selection. Candidate merge
is a durable saga; Candidate 360 is a revision-fenced CQRS projection. Each aggregate embeds its outbox,
which avoids cross-document transactions on a standalone local MongoDB.

Application events trigger candidate projection reads; resume events wake waiting application processing.
Durable polling repairs missed/delayed notifications. Kafka publication is at least once, not exactly once.
The full workflow adds producer=phase3-workflow so it can share the topic with the earlier intake milestone.
Kafka consumers retry database/network/server outages without silently acknowledging the record. Other
repeated failures are published to the source topic plus .DLT; operators must investigate/replay those records.
Dead-letter publication failure does not acknowledge successful recovery.

Screening is deliberately deterministic keyword matching, LOW confidence, with modelVersion
deterministic-keywords-v1. It is not an LLM hiring decision. A failed/missing eligibility answer enters the
human attention queue. The existing Phase 2 local-LLM job-drafting integration remains unchanged.

## UI and operational details

- Consent is fetched from the service and sent with the submission. CONSENT_VERSION must change when
  the configured consent policy changes; this phase is not a tenant-managed legal-text publishing system.
- Career status/withdrawal uses a high-entropy receipt stored in sessionStorage. The server stores its hash.
  Keep the original browser tab; cross-device receipt recovery/email delivery is not implemented.
- Agency submissions require an active assignment created by a staff user through
  PUT /api/v1/partner-assignments/{actorId}/{jobId}. Partner output excludes private notes and scores.
- Agency login returns to PARTNER_UI_URL (default localhost:5174/jobs). The realm seed includes the
  console/partner callback URLs and realm-role claims in ID/userinfo tokens. For an **existing realm**,
  apply these client settings through Keycloak administration; startup import does not update existing realms.
  Do not delete a populated realm just to apply this configuration. See the official
  [protocol mapper reference](https://www.keycloak.org/admin-api/protocol-mappers).
- Employee internal applications must use the employee JWT email. Referral submission does not claim
  to send an email or award a bonus.
- API-key intake is POST /api/v1/public/api-applications with X-API-Key, Idempotency-Key and the
  applications:write scope. The identity service revalidates the key on each write.
- CSV columns: name,email,processingConsent; optional phone,resume. ZIP requires candidates.csv and
  referenced resume files. Maximum 1,000 rows, 10 MB upload, 50 MB expanded archive.
- An import's DONE means all rows have finished submission processing, not that every resume is screened.
  Candidates without resumes enter Needs attention. Exact-email resolution happens asynchronously.
- Pipeline pages contain up to 100 applications. Duplicate review returns up to 100 unresolved pairs,
  without silently limiting candidate matching to the first 500 candidates.
- Shortlisting requires a screened application. Resume replacement clears the old assessment and score;
  undoing a rejection from APPLIED restores pending processing (or the original upload-waiting state).
- Full workflow records use oh_application.submissions. Existing foundation records remain accessible
  through /api/v1/intake/applications and are not silently migrated or deleted.
- Local resume storage supports this single-host deployment. Multi-host shared storage, retention/legal holds,
  calibrated probabilistic identity matching, invitation emails and account-linked cross-device candidate
  portals need further work; they are not represented as completed by these screens.
- Later-stage pipeline labels do not create interview bookings, offers, onboarding or hires in future services.
  Candidate offer/scheduling controls are explicitly unavailable in this phase.

## Verification evidence

Passed during implementation:

- Console suite: 22 files / 62 tests.
- TypeScript checks: console, agency partner, candidate portal and shared API client.
- Docker Compose phase3 configuration validation and launcher shell syntax.
- Cached Gradle dependency resolution and lockfile refresh for the new/changed modules.
- Java compilation and tests for identity, job, approval, application, candidate, resume, shared Phase 3
  support and gateway modules; subsequent startup/CSRF regression tests also passed.
- Opt-in Mongo candidate acceptance tests (tenant isolation, merge preservation and recovery).
- Docker builds and healthy startup of all Phase 3 services, gateway, Tika and ClamAV.
- Real recruiter authorization-code/PKCE sign-in with the expected tenant and role claims.
- Live Phase 2 create → edit → submit → approve → publish workflow.
- Real PDF intake/replay, consent rejection, scan/extraction and keyword assessment through the gateway.

Runtime fixes: distinct security configuration/filter-chain bean names; primary gateway rate-limit resolver;
explicit Boot 4 standard Mongo UUID representation; amd64 ClamAV on Apple Silicon; scanner/parser readiness
dependencies; bounded gateway DNS caching after container replacements; unmasked servlet error statuses;
CSRF request-attribute binding and SPA raw-cookie header compatibility. AuthProvider now checks CSRF
initialization success before reporting an authenticated UI session.

The final CSRF gateway image was built and regression-tested but **not deployed**: automatic approval
review stopped with a workspace-credit error. Live shortlist/undo, withdrawal, download and CSV checks
must be rerun against that image. No backend security checks were disabled to make tests pass.

Not yet verified: complete live staff/agency/employee workflows, Kafka restart/replay, malware/OCR/failure
fixtures, live browser workflows or throughput. These remain acceptance gates, not claims of success.

Resume the final check from backend after deployment approval is available:

```bash
docker compose up -d --no-deps --wait --wait-timeout 60 api-gateway
export OPENHIRE_JOB_ID='fe734ab3-fb7c-4665-ac62-73469758d314'
export OPENHIRE_TEST_USERNAME='recruiter@openhire.local'
# Set OPENHIRE_TEST_PASSWORD securely, or supply OPENHIRE_STAFF_TOKEN instead.
node scripts/testing/verify-phase3-runtime.mjs
```

The runtime script generates a harmless PDF in memory, tests real authentication and CSRF, intake,
screening, Candidate 360, download, shortlist/undo, withdrawal and CSV row outcomes. It creates labelled
development records and retains them. Earlier failed checks left fixture applications
0861f49ee46a2953b79174d81660e2fa7c17bf24c3b904a2e4194fcf4cc0ae19 and
17b72f6699de27e897cfaae751ec5f141deeb8f072d6ca62e315e0553323e3e4 in the development tenant.
No existing records or volumes were deleted. The temporary Mongo acceptance database was removed by its test.

## Owner-run acceptance

From backend:

```bash
./gradlew :application-service:test :candidate-service:test :resume-processor:test :service-support:test
OPENHIRE_RUN_MONGO_TESTS=true ./gradlew :candidate-service:test
./run-backend.sh phase3
```

The opt-in Mongo test creates and drops only a randomly named oh_phase3_test_* database.
The launcher builds Java images. Change default development secrets before nonlocal use. ClamAV's first
signature update can delay readiness; the resume processor now waits for scanner and parser health.

Publish a job through Phase 2 and provide a harmless resume fixture and any required eligibility answers:

```bash
export OPENHIRE_JOB_ID='your-open-job-id'
export OPENHIRE_TENANT_ID='demo-org'
export OPENHIRE_RESUME='/absolute/path/to/clean-fixture.pdf'
export OPENHIRE_ANSWERS='{"question-id":"yes"}'
bash scripts/testing/verify-phase3-workflow.sh
```

The script creates one real development application, verifies replay/receipt security and screening,
then withdraws it. It leaves the application as audit/test data. Separately verify:

1. Recruiter upload and CSV/ZIP import, including invalid rows and missing consent.
2. Scanner unavailable, unreadable PDF, harmless malware-test fixture, retries and clean replacement.
3. Career and assigned-agency applications with the same email converge on one tenant-local candidate.
4. Wrong tenant, unassigned agency and revoked/missing-scope API keys are rejected.
5. Reviewed merge preserves both histories; replay/worker restart and stale projections remain safe.
6. Candidate 360, pipeline pages, score override and human eligibility decisions reflect persisted state.

Run the load test only against an isolated development tenant:

```bash
k6 run scripts/testing/phase3-load.js
```

It requests 500 applications/minute for five minutes plus a short burst and creates roughly 2,700 records.
It measures intake latency/errors, not end-to-end processing drain time; inspect processing backlog and
scanner capacity separately. Public burst rate limiting is expected. No throughput claim is made until run.

Do not start Phase 4 until the owner reviews the Phase 3 evidence.
