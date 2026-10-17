# Phase 4 — interviews, communication and notification dispatch

## Status — 2026-10-15

**Complete and runtime-accepted on the local stack.** Owner decisions still open are listed under *Open items*.

## Services

| Service | Port | Database | Responsibility |
|---|---|---|---|
| `interview-service` | 8087 | `oh_interview` | Interview loops and rounds, panel assignment, booking, candidate self-scheduling, blind scorecards, rule-based debrief |
| `communication-service` | 8088 | `oh_comm` | Conversations, message templates, staff notifications and preferences, digest, candidate preferences |
| `notification-dispatcher` | 8089 | `oh_dispatch` | Email delivery, retries, WhatsApp→email fallback, quiet hours, throttling |

Shared code lives in `platform-starter` (tenancy, audit, event envelope) and `service-support`
(formerly `phase3-support`: workflow security, event relay, Kafka error handling, service client).
Hire/no-hire decisions live in `application-service` (`POST /applications/{id}/decision`).

Events: `interview.events.v1`, `communication.commands.v1` (retry topics `-retry-0..2`, `-dlt`),
`communication.delivery.v1`. Contracts: `contracts/phase4-api.openapi.json`, `contracts/phase4-events.asyncapi.yaml`.

## Behaviour worth knowing

- **No double booking.** A 15-minute block ledger (`panel_blocks`, `_id = tenant|interviewer|blockEpoch`) makes
  reservations atomic. Redis holds offered slots for 30 minutes.
- **Scheduling links** carry an HMAC token in the URL fragment (`#token=`), sent as `X-Scheduling-Token`; the
  portal server never sees it.
- **Candidates can move or cancel** a booking themselves until 24 hours before it starts, within the
  reschedule limit. The current booking stands until the candidate picks a new time. Cancelling tells the panel
  and owner, with the reason, and sends the candidate nothing further.
- **Blind scorecards.** An interviewer sees others' scorecards only after submitting their own; the debrief is
  restricted to people who may read all feedback.
- **Notifications.** In-app and email, per-person preferences, quiet hours (routine notifications only),
  digest. Approvers are notified when an approval is waiting for them (steps without a named approver notify
  the `HIRING_MANAGER` role); requesters are notified of the outcome. Notifications deep-link, e.g. an approval
  opens `/approvals?id=…`.
- **Delivery.** Transactional messages (interview invitations, links) ignore quiet hours. WhatsApp and SMS are
  not configured, so those messages are sent by email with a note on the message.

## Deviations from the plan

- Scorecards are embedded in the loop document rather than a separate collection.
- Commands are keyed by message id (the dedupe key).
- Retry topics are named `-retry-0..2` / `-dlt`, not `.retry.10s`.
- The debrief is rule-based (`generatedBy: RULES`); the AI summary arrives with Phase 6. "Suggest reply"
  returns 409 until then.
- Calendar sync and generated video links are deferred to Phase 8; a video link can be typed in by hand.
- Event `producer` is still `phase3-workflow` for the Phase 3 services (published contract; not renamed).

## Open items

- SMS/WhatsApp providers need an account and credentials (owner decision).
- Job approvals still route to the requester's role ("me"); named hiring-manager routing needs a job field.
- Portal tests: the portal `/schedule` page has no automated browser test yet.
- Operations: Docker Desktop's 8 GB memory is tight for this stack (ClamAV was OOM-killed once); raise it.

## Evidence

All services were rebuilt from current source and force-recreated before these runs.

| Check | Result |
|---|---|
| `./gradlew test --rerun` | Pass: all modules, 0 failures (opt-in Mongo/Testcontainers tests skipped) |
| `verify-phase4-workflow.mjs` | Pass: approver and requester notifications, job with plan, career application + ack email, loop from INTERVIEWING, signed-link booking (forged token and unoffered time rejected), no double booking, candidate move then cancel with panel alert, interviewer notifications and preferences, blind scorecards, debrief, HIRE→OFFER, messaging with WhatsApp→email fallback, candidate reply, templates |
| `OPENHIRE_TEST_OUTAGE=true verify-phase4-workflow.mjs` | Pass: SMTP stopped, message moved through the retry topics, delivered after recovery |
| `verify-phase1-runtime.sh`, `verify-phase2-workflow.sh` | Pass |
| `verify-phase3-workflow.sh`, `-intake.sh`, `-runtime.mjs`, `-roles.mjs` | Pass |
| `e2e-live/phase4-workflow.spec.ts` | Pass, 6 consecutive runs: real sign-in, assign panel, book, complete, scorecard, hire (application reaches OFFER), new message with delivery status |
| `e2e-live` phases 1–3 | Pass |
| Frontend: typecheck, lint, tests, candidate build | Pass (console 69 tests; `/schedule` builds) |

Fixes made while accepting:

- The gateway's role→permission map predated Phase 4, so recruiters could not open Interviews and hiring managers
  could not see interviews or record decisions. It now grants `interview:schedule` to recruiters and the
  decision/scorecard permissions to hiring managers.
- A malformed `answers` field on a public application caused a 500; it now returns 400 (`Answers.parse`).
- "Book a time" showed no confirmation because the booked row left the queue before the mutation callbacks ran.
- `pathFor` now turns non-path parameters into a query string, so notifications can deep-link
  (`/approvals?id=…`).
- The Interviews screen has previous / next / this-week navigation (`?week=YYYY-MM-DD`, Monday-aligned).
- `phase3-support` was renamed `service-support` (package `com.openhire.support`).

Not exercised by a browser: the candidate portal's `/schedule` and Messages pages (built and type-checked; their
APIs are covered by the acceptance script).
