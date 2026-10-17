# Phase 2 — jobs, approvals and hiring configuration

## Delivery status

Phase 2 implementation is complete and runtime-accepted on 2026-10-09:

- `scripts/testing/verify-phase2-workflow.sh` passes (create → edit → submit → approve → publish through the gateway).
- The live browser spec `apps/console/e2e-live/phase2-workflow.spec.ts` passes against the real stack with a
  real Keycloak sign-in (5 consecutive passes). It previously had no sign-in step and two ambiguous selectors;
  both were fixed in the spec, not the product.
- `@openhire/api-client` lint was failing (`react-hooks/rules-of-hooks`); the internal job-lifecycle helper is
  now `useLifecycleMutation`, with no behaviour change.

Still open: AI job-description drafting cannot be exercised because no local LLM is installed; without one
the endpoint returns a clear 409 ("The local LLM is unavailable…"). Job approvals currently route to the
requester (`"approver": "me"` in `HttpApprovalClient`), which is a development simplification to revisit
when real approval chains are configured.

Phase 3 intake foundation started on 2026-10-07 following the owner's explicit request. See `PHASE_3.md` for its separate delivery status.

## Completed scope

- Tenant-scoped job aggregate with immutable version snapshots and MongoDB optimistic locking.
- Draft, edit, submit, request-changes, approve, publish, pause, close and reopen lifecycle.
- `If-Match` enforcement for job edits and hiring-team changes.
- Structured salary, skill, knockout-question, rubric, hiring-team and interview-plan data.
- Hiring-stage sets and pipeline templates.
- Local-LLM job-description drafting behind an application port.
- Approval requests with sequential and parallel groups, assigned-actor validation, rejection reasons, request changes and undo.
- Ad-hoc delegation, scheduled delegation periods, SLA due dates, overdue queries and reminder events.
- Job-to-approval request integration and approval-to-job decision callback protected by an internal secret.
- Public jobs, internal jobs and Today inbox projections.
- Job and approval outbox relays to Kafka with versioned event envelopes.
- Service-level Keycloak role conversion and method-level RBAC.
- Tenant isolation tests and role-restricted write endpoints.
- OpenAPI and AsyncAPI contracts.
- React hooks and screens wired to live lifecycle endpoints with matching development mocks.

## Lifecycle

```text
DRAFT -> PENDING_APPROVAL -> approved version -> OPEN -> PAUSED/CLOSED -> OPEN
   ^             |
   +-- request changes / rejection
```

Only the currently approved immutable version can be published. Editing creates a new version and clears approval eligibility.

## Patterns

| Concern | Pattern |
|---|---|
| Job and approval rules | Aggregate plus state machine |
| Job history | Immutable version snapshots |
| Approval ordering | Chain of responsibility with parallel groups |
| Service integration | Ports/adapters and internal-secret callback |
| Concurrent editing | Optimistic concurrency with `If-Match` and Mongo `@Version` |
| Reliable event publication | Outbox relay |
| Duplicate commands | Redis idempotency key |
| LLM provider | Strategy/anti-corruption adapter |
| Access control | Keycloak RBAC plus assigned-approver rule |

## Verification evidence

- API client and console TypeScript checks pass.
- Console test suite: 22 files and 62 tests pass.
- Console production build passes.
- Docker Compose parses successfully.
- Phase 1 and Phase 2 acceptance scripts pass shell syntax validation.
- Earlier backend compile/test verification passed before the final security additions. Re-run the Gradle command below manually because this Codex sandbox blocks Gradle file-lock sockets.

```bash
cd /Users/rabiulbiswas/Desktop/openhire-web/backend
GRADLE_USER_HOME="$PWD/.gradle-user" ./gradlew --no-daemon spotlessCheck test
```

## Live review gate

With the services and UI running, execute the API workflow:

```bash
cd /Users/rabiulbiswas/Desktop/openhire-web/backend
./scripts/testing/verify-phase2-workflow.sh
```

Then execute the browser workflow:

```bash
cd /Users/rabiulbiswas/Desktop/openhire-web
OPENHIRE_LIVE_E2E=true pnpm --filter @openhire/console test:e2e:live
```

Both workflows cover create → edit → submit → approve → publish. Stop and review these results before beginning Phase 3.
