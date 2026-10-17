# OpenHire — remaining work by phase

Audit date: 2026-10-16. Repository: `openhire-ai` (`backend/` and `frontend/`).

## Scope and confidence

This is a documentation and targeted source review, not a fresh runtime certification. No Java builds, tests, services, live provider calls or AWS deployments were run for this audit. Existing phase notes record historical test results; those results are not evidence that this new checkout or a future deployment passes today.

Baseline: the phase definitions in [the implementation-plan source](../../frontend/docs/build_backend_implementation_plan.py), current [phase notes](#phase-summary), service guides, source/configuration and test inventory. The plan generator contains stale approval/status entries; later dated implementation notes take precedence for delivery evidence. This is not an exhaustive line-by-line audit of every original HLD/LLD requirement.

Labels:

- **BUILD** — documented or source-confirmed missing implementation.
- **VERIFY** — implementation exists or is reported, but acceptance evidence is still needed; not automatically missing code.
- **DECIDE** — scope/provider/architecture decision required before implementation.
- **DEFERRED** — intentionally outside the currently authorized work: deployment and live LLM hosting.
- **RECONCILE** — conflicting or historical documentation needs updating, not necessarily new code.

All unchecked items below are outstanding actions, not a declaration that each entire phase is unfinished. A phase can have accepted core workflows and still have optional extensions or production-readiness work remaining.

## Phase summary

| Phase | Current evidence | Remaining work |
| --- | --- | --- |
| [0 — Foundation](PHASE_0.md) | Build/test foundation and new affected-service CI/CD definitions exist | First GitHub execution/configuration, scan evidence, complete contract coverage and design deviations |
| [1 — Identity](PHASE_1.md) | Core local runtime accepted in historical notes | Owner-assisted authentication checks, full security acceptance and planned-flow traceability |
| [2 — Jobs/approvals](PHASE_2.md) | Create → edit → submit → approve → publish historically accepted | Actual hiring-manager routing and shared job ownership model |
| [3 — Intake/resumes](PHASE_3.md) | Core intake/security/browser flows historically accepted | Load test, multi-node resume storage, candidate-access/identity scope decisions |
| [4 — Interviews/messages](PHASE_4.md) | Core workflow historically accepted | Candidate scheduling browser coverage; optional channel providers; Phase 8 calendar completion |
| [5 — Offers/hires](PHASE_5.md) | Core offer and hire saga historically accepted | Saga UI, expiry reminders, approval-mode scope and external downstream adapters |
| [6 — AI/talent](PHASE_6.md) | Local model acceptance recorded | Invitation race, scale limits, approved evaluations and architecture decisions; live AI deferred |
| [7 — Governance/operations](PHASE_7.md) | Monitoring/retention implementation and isolated tests recorded | Full runtime gate still open, external alerts and production durability/precision |
| [8 — Enterprise/hardening](PHASE_8.md) | Extensive offline implementation; not fully accepted | Connector completion, enterprise interoperability, quotas, plugins, DR and production acceptance |

## Phase 0 — engineering foundation

- [x] **BUILD — workflows added, 2026-10-17.** Following the owner's expanded CI/CD request, `.github/workflows/ci.yml` and `deploy.yml` now define affected-service Java 25 builds/tests, frontend checks, reports and opt-in ECR/EKS delivery. Deployment remains off by default. See the [pipeline guide](../../.github/README.md).
- [ ] **VERIFY — first GitHub execution and configuration.** Commit/push the complete source tree, configure branch protection/environments and demonstrate PR/main checks on real runners. AWS delivery additionally needs the documented IAM/ECR/EKS configuration and explicit enablement. Local workflow validation is not cloud acceptance.
- [ ] **BUILD/VERIFY — isolated CI integration dependencies.** Explicitly enable intended Mongo/Redis/Kafka integration tests and report skipped tests. Use disposable dependencies; mock live providers/LLM. Closure: a passing unit job cannot conceal skipped required integration coverage.
- [ ] **VERIFY — vulnerability scan evidence.** Phase 0 records this as not run. Configure the scanner's required credentials securely and resolve findings or record reviewed exceptions. Do not assume later successful compilation implies a successful security scan.
- [ ] **BUILD — machine-readable contract completeness and drift gates.** This checkout has REST/event contracts through Phase 7, but no Phase 8-named contract files. Add/validate connector, calendar, webhook, MCP and enterprise-identity schemas and ensure extended identity routes are covered. The generated Markdown endpoint references do not replace OpenAPI/AsyncAPI or compatibility tests.
- [ ] **VERIFY — clean reproducible build.** Run from the new repository layout with the committed wrapper/lockfiles, all active modules, reports and required infrastructure tests. Existing copied `build/` directories are not acceptance evidence.
- [ ] **RECONCILE — foundation architecture deviations.** Document why `contract-catalog`, `test-support` and `demo-seeder` are folders/shared facilities rather than independent modules, or implement them only if still required. Verify the actual outbox consistency design against the plan's replica-set/transaction language.
- [ ] **RECONCILE — service inventory.** There are 18 executable modules; integrity runs inside `ai-workers`, not a nineteenth `integrity-worker`. Resolve the plan discrepancy with Phase 6/8 rather than declaring a missing service automatically.

Not missing: Groovy Gradle was explicitly requested and the copied plan source already specifies Groovy. The old Phase 0 instruction to convert back to Kotlin is stale. Architecture tests also now exist beyond the original three services; check coverage rather than claiming they are all absent.

## Phase 1 — identity, tenancy and gateway

- [ ] **VERIFY — owner-assisted authentication.** Complete real TOTP enrollment, verification-email and password-reset delivery/expiry/reuse checks. Phase 1 explicitly leaves these owner-run. Use a test mailbox and test identities.
- [ ] **VERIFY — complete planned security matrix.** Record key rotation, brute-force lockout, account-enumeration resistance, refresh/session expiry and all role/tenant boundary outcomes. Existing login/logout acceptance does not certify every planned case.
- [ ] **VERIFY/DECIDE — magic-link and preserved-draft reauthentication scope.** These appear in the baseline plan. Map each to a current implementation and test; if absent, implement or explicitly approve a scope adjustment. This review did not establish complete end-to-end coverage.
- [ ] **BUILD/VERIFY — quota enforcement across entry paths.** Coordinate invitation/manual-user/SCIM admission with atomic seat enforcement under Phase 8. Existing quota settings and integration rate budgets are not proof of seat-cap enforcement.
- [ ] **VERIFY — candidate cross-device access.** Reconcile receipt-based portal access with the planned candidate account flows. Prove secure ownership, recovery and expiration before claiming account-linked cross-device access.

Dependencies: SAML/SCIM completion belongs to Phase 8. Normal staff login does not need those integrations. Do not reopen the already-recorded sign-out fix without a reproduced regression.

## Phase 2 — jobs, approvals and hiring configuration

- [ ] **BUILD — real approval routing.** [HttpApprovalClient](../job-service/src/main/java/com/openhire/jobservice/adapter/out/approval/HttpApprovalClient.java) still sends `"approver": "me"`. Replace this development simplification with an explicit job/tenant approval policy and authorized named approvers or defined role groups. Test requester exclusion where required, delegation, missing approver and parallel/sequential outcomes.
- [ ] **BUILD — explicit hiring-manager ownership.** Phase 4/5 notes identify the missing hiring-manager field/default. Connect the job model, UI and approval routing, and use it for offer reporting-line defaults. Preserve existing immutable version and concurrency rules.
- [ ] **VERIFY — regression after routing changes.** Repeat create → edit → submit → approve → publish with distinct requester/approver identities and tenant isolation; verify later edits invalidate approval as intended.
- [ ] **RECONCILE — old LLM blocker.** The Phase 2 note says no model was installed; Phase 6 records later real-model job-draft testing. That historical blocker is superseded. Current no-LLM operation still needs a dedicated acceptance check, not another drafting implementation.

Dependencies: these core approval changes do not need a live LLM or external calendar credentials.

## Phase 3 — application intake, resume processing and Candidate 360

- [ ] **VERIFY — planned intake load gate.** Run `scripts/testing/phase3-load.js` only against an authorized isolated test tenant/job. The notes record roughly 2,700 generated applications and no completed load run. Measure processing drain time and scanner backlog in addition to intake latency/errors.
- [ ] **BUILD — multi-node resume storage for EKS.** The accepted local implementation uses a single-host volume. Select shared storage/object storage, then preserve quarantine, malware scanning, signed/authorized downloads and retention/legal-hold behavior across replicas. A PVC declaration alone does not prove multi-node access safety.
- [ ] **VERIFY/DECIDE — candidate receipt recovery and account linking.** Phase 3 explicitly lacked cross-device receipt recovery; reconcile later portal changes before deciding exactly what code remains. Test lost receipt, wrong account, expiry and tenant isolation.
- [ ] **DECIDE — probabilistic identity matching.** Current accepted merging uses deterministic/reviewed suggestions. Decide whether calibrated probabilistic matching from the original scope is required; if so, add evaluation thresholds and false-merge tests before enabling it.

Not missing: ZIP import, real scan/OCR fixtures, role isolation and Kafka restart tests are recorded as passed in the newer Phase 3 status. Earlier contradictory “not yet verified” paragraphs are historical. Retention/legal holds were subsequently implemented in Phase 7; their full acceptance remains under Phase 7.

## Phase 4 — interviews, communication and dispatch

- [ ] **BUILD — dedicated candidate scheduling browser tests.** Add public `/schedule` coverage for valid/forged/expired tokens, offered slots, competing bookings, time zones, reschedule and cancel. The phase notes explicitly identify this coverage gap; console workflow tests are not a substitute.
- [ ] **DECIDE/BUILD — SMS and WhatsApp adapters.** Email is implemented; the other channels are explicitly unconfigured and fall back to email. If required, choose providers and implement contract tests before live certification. Disabled channels are an intentional initial-plan boundary, not a core email defect.
- [ ] **BUILD — calendar completion through Phase 8.** Candidate calendar invitations, generated meeting URL write-back and connector migration/reconciliation remain outstanding there. Do not count the entire calendar integration as missing: provider queue/write support exists.
- [ ] **VERIFY — stable scanner capacity for full workflows.** Historical OOM failures require resource measurement and bounded workloads. Do not bypass scanning or assume increasing Docker memory is the only solution.

Job approval routing is tracked once under Phase 2. Interview AI/question kits are a scope decision under Phase 6, not a prerequisite for manual scheduling.

## Phase 5 — offers, hire saga and employee workflows

- [ ] **BUILD — hire-saga operations screen.** Phase notes describe listing/retry APIs and frontend hooks but no dedicated screen. Add authorized saga status, failed-step details and reasoned retry controls; test tenant isolation and safe repeat actions.
- [ ] **BUILD — expiring-soon reminders.** Scheduled expiry exists; pre-expiry candidate/recruiter notifications are documented as absent. Add configurable timing, deduplication, preference handling and no reminders for accepted/declined/rescinded offers.
- [ ] **DECIDE/BUILD — richer approval modes.** Resolve the documented `ALL | ANY` requirement versus one role per step. If retained, implement concurrency-safe completion and requester/actor rules; otherwise record an approved scope deviation.
- [ ] **DECIDE/BUILD — offer document and external signature scope.** Current letter is text plus checksum, not a rendered signed PDF or certified e-sign integration. Choose the required artifact and provider; retain immutable accepted versions and explicit status transitions.
- [ ] **BUILD — actual HRIS synchronization.** Hire-saga completion currently includes a downstream request event, not proof of an external HRIS employee record. Implement the selected Phase 8 adapter and reconciliation/status handling.
- [ ] **BUILD — candidate portal automated component coverage.** The candidate package has a Vitest dependency but no test script in its current `package.json`. Add an executable test setup and offer/status/account coverage. Existing live offer browser acceptance should be retained.

Dependencies: reporting-line defaults depend on Phase 2 ownership; HRIS/e-sign require provider decisions, but their mocked implementation need not wait for live keys.

## Phase 6 — AI matching, talent and integrity

- [ ] **BUILD — atomic invitation cooldown.** [InvitationService](../talent-service/src/main/java/com/openhire/talent/application/InvitationService.java) reads the previous invite, sends, then saves. Concurrent requests can both pass the check. Add durable atomic admission/idempotency and recovery for send/save failures, plus a competing-request test.
- [ ] **BUILD/DECIDE — scalable talent-vector retrieval.** Current exact in-memory cosine search is documented as unsuitable for the large sample scenario. Benchmark and choose indexed/vector retrieval when required, preserving tenant/consent filtering. Do not add a new database solely to match a diagram.
- [ ] **BUILD — bounded AI operations aggregation.** Request-log queries currently permit up to 100,000 rows for operations reporting. Replace or constrain expensive in-memory reporting with indexed aggregations/materialized metrics and load tests.
- [ ] **VERIFY — approved evaluation data and capacity.** Obtain representative, sanitized examples and review quality, grounding, bias/counterfactual behavior, concurrency and latency. The historical small golden-set pass is not production model approval.
- [ ] **DECIDE — interview AI and candidate assistant.** Question kits, AI scorecard summaries and grounded candidate Q&A remain outside the implemented UI scope described by Phase 6. Approve inclusion or explicitly retain rules-based behavior.
- [ ] **RECONCILE — architecture deviations.** Record the custom model-provider SPI versus planned Spring AI and embedded integrity worker versus a separate executable. These are design choices unless a requirement demands migration/splitting.
- [ ] **DEFERRED/VERIFY — model hosting and provider acceptance.** Live AWS LLM hosting is excluded from the initial deployment discussion. Hosted-provider certification and inference scaling remain future gates. Offline tests should use mocks.
- [ ] **VERIFY — explicit no-LLM mode.** Prove startup and manual hiring flows work without a model, AI actions clearly report unavailability, retries remain bounded and fallback scores are labelled. AI-dependent tests must be reported as intentionally excluded, not silently counted as passing.

## Phase 7 — compliance, analytics and operations

- [ ] **VERIFY — complete runtime acceptance.** The latest notes report the gate blocked by unhealthy ClamAV. After safe capacity correction and owner-run deployment of the changed services, execute the Phase 7 API/browser workflows with the scanner healthy.
- [ ] **VERIFY — distributed privacy and audit journey.** Demonstrate decision provenance, every required export/erase acknowledgement, legal holds, record retention, fairness, metric reconciliation, tenant isolation and DLQ replay. Preserve evidence and avoid real candidate deletion during testing.
- [ ] **BUILD — external alert delivery.** Prometheus rules are implemented and tested; a notification receiver/escalation path is not configured. Connect and test a chosen receiver before production.
- [ ] **BUILD/DECIDE — production SLO precision.** Current health-poll availability and sampled latency indicators are explicitly approximate. Define required SLOs and add request-based counters/histograms and validated alert windows if accurate percentiles/availability are required.
- [ ] **BUILD/DECIDE — independent audit durability.** Logical audit archives live in the same Mongo database. Choose and implement an independent backup/retention strategy appropriate to requirements; do not equate hash chaining with protection from database loss or privileged rewriting.
- [ ] **VERIFY — super-admin acceptance coverage.** Trace quota and break-glass behavior against the plan, including reason, expiry, notifications and restricted candidate access; quota settings alone are not enforcement. Seat/storage caps are tracked in Phase 8.

Not missing: optional low-memory Prometheus integration, audit archival execution and AI-log retention execution are implemented with isolated test evidence in [Phase 7 operations](PHASE_7_OPERATIONS.md). Do not implement them again. Tenant-wide legal holds conservatively blocking AI-log deletion are a documented safety boundary, not permission to weaken retention protection.

## Phase 8 — enterprise integrations and production hardening

Latest detailed scope: [Phase 8](PHASE_8.md) and [enterprise/recovery checkpoint](PHASE_8_ENTERPRISE_RECOVERY.md).

### Connector and delivery implementation

- [ ] **BUILD — OAuth lifecycle completion.** Provider-side revocation where supported, key/token encryption rotation procedures, distributed refresh serialization, provider-specific retry timing and circuit breakers. Local disconnect and refresh compare-and-set already exist; do not describe them as provider consent revocation or a complete refresh lock.
- [ ] **BUILD — calendar lifecycle completion.** Candidate invitations, meeting URL write-back, safe connector replacement/migration, reconciliation of existing events and explicit disable/cancel semantics.
- [ ] **BUILD — configuration-create idempotency.** Add retry-safe creation keys and conflict behavior for connector/webhook configuration creation; existing queue idempotency does not cover every create API.
- [ ] **BUILD — queue lifecycle limits.** Add backlog/storage bounds and retention for delivery/work history. Existing per-window admission budgets do not constitute complete queue retention or storage quotas.
- [ ] **VERIFY — webhook/network acceptance.** Real TLS/SSRF-boundary tests, duplicate/replay handling and full 24-hour outage/retry acceptance. Existing mocked transport tests do not certify deployed network behavior.
- [ ] **DECIDE/BUILD — extra adapters.** Choose e-sign, HRIS and job-board providers, then implement mappings, auth, idempotency, retries, status/reconciliation and contract tests. No named providers have been selected; generic configuration placeholders are not working integrations.
- [ ] **BUILD/DECIDE — versioned plugin SPI.** Define connector capabilities, version compatibility and loading/isolation. Existing provider ports do not by themselves establish the planned extensible plugin lifecycle.

### Enterprise identity, MCP and quotas

- [ ] **VERIFY — real Google/Microsoft certification.** Configure sandbox OAuth apps and approved redirect URIs, then demonstrate connect, refresh, read/write/update/cancel, disconnect and tenant isolation. Live credentials are needed only for this gate, not offline implementation.
- [ ] **VERIFY — SAML interoperability and rollover.** Test signed assertions, prelinked-only login, wrong-tenant denial, certificate expiry/rotation and recovery of interrupted configuration changes. Configuration APIs are already implemented.
- [ ] **VERIFY — actual SCIM client interoperability.** Prove Users create/update/disable, session effects, retries, conflicts and recovery against the selected enterprise client. Users APIs are already implemented; they do not implement the entire SCIM standard.
- [ ] **DECIDE/BUILD — SCIM Groups and broader profile.** Groups/role mapping, complex filters, username changes and bulk operations are explicitly unsupported. Add only required capabilities with authorization tests, or formally accept the constrained Users profile.
- [ ] **BUILD — seat/storage quotas and operations UI.** Enforce atomically across invitations, provisioning and storage uploads; add clear operator visibility. Integration admission budgets already exist and must not be relabelled as commercial quota completion.
- [ ] **VERIFY — real MCP OAuth/transport acceptance.** Register the delegated client, audiences/scopes and origins; verify approved write actions and refusal of self-approval using an actual client. Add an operational reconciliation procedure for interrupted `EXECUTING` confirmations.

### Recovery, deployment and release readiness

- [ ] **BUILD/VERIFY — coordinated full-platform recovery.** Define RPO/RTO and recovery ordering for all Mongo databases, Keycloak, Kafka offsets/replay, Redis sessions, resume storage, secrets and encryption keys. Rehearse restored application invariants and audit verification. The encrypted single-database fixture restore already passed; it is not full-system DR.
- [ ] **BUILD — production runbooks and migrations.** Cover upgrades, compatible schema/event evolution, key rotation, uncertain external writes, queue exhaustion, incident response and recovery ownership.
- [ ] **DEFERRED — Hyderabad EKS deployment.** Review ALB integration, network policies, secret delivery, persistent resume storage, dependency hosting, resource sizing, autoscaling and multi-zone scheduling. Current Helm defaults use NGINX ingress and disable resume processing pending storage. Offline chart rendering is not deployment acceptance.
- [ ] **DEFERRED/VERIFY — release acceptance.** Run load/chaos/security tests, rolling updates, node/broker failures and restore drills when deployment is authorized. No deployment or CD pipeline is part of the current CI-only request.

## Cross-phase closure rules

- [ ] Update historical absolute paths from `openhire-web` to instructions relative to `openhire-ai/backend` or `openhire-ai/frontend`; retain dated evidence rather than rewriting history.
- [ ] Maintain one requirement → endpoint/UI → test mapping; close a gap with a source change plus evidence, or an explicit approved scope decision.
- [ ] Keep core acceptance, optional extensions and production hardening separate. Existing phase labels saying “complete” do not remove their documented open items.
- [ ] Record skipped live-LLM/provider tests explicitly. No live keys in Git, chat, generated reports or fixtures.
- [ ] Never weaken authentication, tenant isolation, malware scanning, legal holds or approval controls to obtain a passing test.

## Recommended execution order

1. Phase 0 CI-only setup for this checkout: build, tests, reports; no deployment.
2. Independent business gaps: Phase 2 routing/ownership, Phase 5 saga UI/reminders, Phase 6 invitation race and portal tests.
3. Contract drift/coverage and no-LLM acceptance across core workflows.
4. Phase 7 full runtime acceptance when the owner-operated stack is healthy.
5. Phase 8 offline implementation: calendar lifecycle, quotas, idempotency and recovery/runbooks.
6. Provider decisions, followed by provider-specific adapters and sandbox certification.
7. Separately approved AWS deployment, production sizing and live release gates.

This document creates a backlog only. It does not authorize deployment, implementation of every optional feature, production data changes, or starting new paid infrastructure.
