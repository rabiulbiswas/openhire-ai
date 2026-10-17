# Phase 8 — integrations and production hardening

Status: implementation in progress; not complete. Prioritized providers: **Google Calendar and Microsoft 365**. Live keys are not required for development or the offline test suite. Do not interpret passing mocked-provider tests as provider certification.

The latest enterprise identity, quotas and recovery slice is documented in [PHASE_8_ENTERPRISE_RECOVERY.md](PHASE_8_ENTERPRISE_RECOVERY.md): SAML configuration/pre-linking, a scoped SCIM Users profile, atomic integration admission budgets, and encrypted Mongo backup/isolated restore tooling are implemented. Provider-specific additional adapters still need provider selection. The limitations and acceptance requirements in that document take precedence over older checkpoint notes below.

## 8A: configuration foundation

Implemented `integration-hub` using Java 25, Spring Boot 4, the existing shared security/audit infrastructure and MongoDB. Added gateway routes and connected the console's Connectors and Webhooks screens to persisted configuration APIs. The optional Compose profile caps this service at 512 MB; Docker's overall memory allocation is unchanged. This still requires available capacity and is not a guarantee that the full stack fits.

Saving configuration alone does not connect a provider or enable delivery. The configuration response describes configuration state, not live health. Use the separate connection endpoint to check OAuth state, then the calendar read endpoint to verify provider access. Only tenant-scoped `secret://<tenant-id>/...` references are accepted, never raw tokens; references are not returned by list/create/update responses. Older references outside the tenant namespace must be updated before use.

## 8B: implemented without live keys

- Google Calendar and Microsoft 365 authorization-code flow with PKCE, random one-use state bound to tenant/admin/configuration revision, ten-minute expiry and atomic consumption.
- Read-only mounted secret resolution beneath a configured directory, including real-path containment checks. Missing credentials produce 503 rather than fake success.
- AES-256-GCM encrypted access/refresh tokens and PKCE verifiers; encryption associated data binds token records to their tenant/connector. Refresh uses compare-and-set revisions. Disconnect removes token ciphertext, invalidates pending state and advances a tombstone revision so stale refresh/authorization cannot restore credentials.
- Fixed provider origins, no HTTP redirects, request timeouts and four in-flight requests per provider. Provider errors are sanitized. Calendar event listing, token refresh, create/update/cancel, and opt-in interview synchronization are implemented as described below.
- Console authorization, callback completion, live access check and local disconnect controls. Disconnect does **not** revoke the app consent at Google/Microsoft; the response explicitly says so.
- Durable Mongo webhook queue with tenant-scoped delivery history, event deduplication, expiring worker leases, HMAC-SHA256 signatures, retry backoff for up to 24 hours, terminal failed delivery state and audited replay. Updated/disabled configurations cancel their older queued deliveries.
- Event aliases map `ApplicationSubmitted`, `interview.scheduled.v1`, `offer.accepted.v1`, and `employee.created.v1` to the four external aliases. Payloads contain event/resource/tenant identifiers only, not internal candidate/offer payloads.
- HTTPS dispatch validates resolved addresses, pins the connection to the validated address, retains TLS hostname verification/SNI, rejects private/reserved destinations and does not follow redirects. Dispatch is disabled by default. DNS resolution uses the host's resolver; configure bounded DNS timeouts operationally.

Additional endpoints (all administrator-only under `/api/v1`):

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/connectors/{id}/authorize` | Return provider authorization URL |
| POST | `/connectors/oauth/callback` | Exchange `{state, code}` for encrypted tokens, bound to current admin |
| GET | `/connectors/{id}/connection` | Return authorization state, never tokens |
| GET | `/connectors/{id}/calendar/events` | Refresh if needed and read up to 50 calendar events |
| DELETE | `/connectors/{id}/connection` | Remove local credentials and invalidate outstanding authorizations |
| GET | `/webhooks/delivery-status` | Report operator-controlled dispatch setting |
| GET | `/webhooks/{id}/deliveries` | Latest 100 deliveries, without body or lease secrets |
| POST | `/webhooks/{id}/deliveries/{delivery}/replay` | Requeue a tenant-owned terminal failed delivery |

## 8C: calendar writes and interview synchronization

Calendar writes use a durable Mongo desired-state queue rather than an HTTP request remaining open until the provider responds. `PUT /api/v1/connectors/{id}/calendar/work/{resource}` accepts `{version, event, cancel}` with an `Idempotency-Key` header; use version 0 for a new resource. `GET` on the same path returns the current operation status. Event fields are `title`, `start`, `end` (UTC instants), `timezone` (IANA zone), `attendees` (email array) and `onlineMeeting` (boolean). Validation limits meetings to 24 hours and 30 attendees. Cancellation permits a null event.

The worker is disabled unless `INTEGRATION_CALENDAR_WRITES_ENABLED=true`. Writes use optimistic versions, stable provider creation identifiers, expiring leases, a 24-hour retry deadline, sanitized failure codes and receipt reconciliation when the desired state changes during an in-flight request. Google uses a deterministic event ID with conflict reconciliation; Microsoft uses a transaction ID followed by an update to reconcile the desired content. Optional Google Meet/Teams creation is supported when creating an event, subject to provider account capabilities. Live behavior is not yet certified.

`GET/PUT /api/v1/connectors/interview-sync` reads/updates the tenant binding using `{connectorId, enabled}`. The administrator must explicitly enable it. Scheduling, rescheduling and cancellation events after activation are consumed from `interview.events.v1`; older source events cannot replace newer desired state. Panel emails are included. Candidate invitations and writing the generated meeting URL back to the interview service remain open. Changing a bound connector requires a migration/reconciliation workflow; it is deliberately rejected rather than silently leaving duplicate meetings. Disabling sync stops future event capture, not already queued work or existing provider events.

Patterns: desired-state reconciliation, idempotent consumer, optimistic concurrency, lease-based work queue, provider strategy through a port, bulkhead and bounded retry. Components: Kafka consumer, Mongo queue, Google Calendar REST API, Microsoft Graph, encrypted OAuth grants and administrator UI controls.

## 8D: scoped MCP tools and human approval

`POST /api/v1/mcp` implements the stateless JSON-response MCP transport for protocol `2025-06-18`, with `initialize`, `ping`, `tools/list`, and `tools/call`. Tools currently provided:

- `connectors.list`: tenant-scoped configuration read, 100 records per page; pass the returned `nextCursor` as the next call's `after` argument. Credentials and secret references are omitted.
- `connectors.disable`: first requests a five-minute approval; it cannot disable anything until the same administrator explicitly approves in the console and the client resumes with `confirmationId`. Approvals bind tenant, actor, connector and configuration version and are atomically single-use. Changed configurations require new approval.

The dedicated gateway security chain accepts bearer authentication, does not use browser sessions, and disables CSRF only for that bearer endpoint. The regular console routes retain CSRF protection. The integration service independently requires an administrator role, JWT audience `openhire-integrations`, and `integrations.read` (plus `integrations.write` for the write tool). Browser approval requires authorized party `openhire-web`, so the MCP client token cannot approve its own action. Origins are allowlisted. Register a separate delegated OAuth client and audience/scope mappings in the identity provider before connecting a real MCP client; this is not auto-provisioned.

Protected-resource metadata is exposed at `/.well-known/oauth-protected-resource/api/v1/mcp`. Set `INTEGRATION_PUBLIC_BASE_URL` consistently on gateway and integration-hub to the externally visible HTTPS gateway URL. Discovery alone does not configure OAuth clients or grant scopes.

Console approval endpoints are `GET /api/v1/connectors/mcp-confirmations` and `POST /api/v1/connectors/mcp-confirmations/{id}/decision` with `{approve: true|false}`. A crash after disabling but before recording execution may leave an `EXECUTING` record; there is no automatic replay of such a write. Audit events record tool use and human decisions without credentials.

## 8E: webhook operations and deployment templates

The console can inspect delivery history and explicitly replay a terminal failed delivery. It reports the operator's dispatch setting rather than presenting saved configuration as successful delivery. Configuration lists have cursor pagination (`after`, `limit`, maximum 200), and webhook event fan-out walks all pages instead of silently stopping at 200 subscriptions. Fan-out is not a point-in-time snapshot; concurrent configuration changes take effect according to the dispatch revision checks.

`backend/infra/helm/openhire` supplies deployment/service templates, readiness/liveness probes, rolling updates, disruption budgets, optional autoscaling, optional TLS ingress and ingress network policies. Containers run non-root with a read-only root filesystem and dropped capabilities. Operators supply existing secrets and external infrastructure; resume processing additionally needs its PVC configured. Helm lint and offline rendering have passed; no cluster deployment or rolling-upgrade acceptance has run. The chart covers the 18 executable modules currently present; the plan's separate nineteenth `integrity-worker` application is not silently invented. Chart defaults are production-oriented and are not intended to fit the local Docker 8 GB allocation.

### Configuration when credentials become available

`INTEGRATION_ENCRYPTION_KEY` is a base64-encoded 32-byte key, supplied through deployment secrets, never committed. `INTEGRATION_SECRETS_DIRECTORY` points to a read-only mounted secret directory. A reference `secret://demo-org/google` resolves to `<directory>/demo-org/google.json` with `clientId`, `clientSecret`, `redirectUri` and optional Microsoft `directory` (directory UUID or `organizations`). The redirect URI is the console's `/admin/connectors` URL. HTTPS is required except localhost development. The browser removes authorization parameters from its URL and requires the administrator to finish the callback while signed in.

For webhooks, the reference's JSON contains `signingKey`, a base64 key of at least 32 bytes. Set `INTEGRATION_WEBHOOKS_ENABLED=true` only after mounting keys and reviewing destinations. Compose passes the settings, but operators must add a read-only mount for the configured directory; no secret files or fake live credentials are created automatically.

Receiver signature input is UTF-8 `<timestamp>.<delivery-id>.<exact-json-body>`, HMAC-SHA256, lowercase hex. Headers are `X-OpenHire-Timestamp`, `X-OpenHire-Delivery`, and `X-OpenHire-Signature: v1=<hex>`. Receivers must check a five-minute timestamp window and persist delivery IDs to reject duplicates; retries/replays retain the delivery ID and use a new timestamp/signature. Delivery is at-least-once, not exactly-once.

### Business logic and endpoints

All endpoints require an authenticated organization or super administrator with tenant context. Base path `/api/v1`:

| Method | Path | Business logic |
| --- | --- | --- |
| GET | `/connectors/catalog` | Return the two selected providers and planned OAuth scopes |
| GET | `/connectors` | Cursor-paged tenant configurations; `after` and `limit` (1–200) |
| POST | `/connectors` | Validate and create a provider configuration at version 1 |
| PUT | `/connectors/{id}` | Update using the supplied expected version; stale writes return 409 |
| GET | `/webhooks` | Cursor-paged tenant webhook configurations; `after` and `limit` (1–200) |
| POST | `/webhooks` | Register an HTTPS endpoint and supported event aliases, without delivery |
| PUT | `/webhooks/{id}` | Update configuration using optimistic concurrency |

Create/update requests contain `name`, `secretRef`, `enabled`, and either `provider` or `endpoint` plus `eventTypes`. Updates also require `version`. Provider IDs are `GOOGLE_CALENDAR` and `MICROSOFT_365`. External event aliases are `application.created`, `interview.scheduled`, `offer.accepted`, and `employee.activated`; the internal mappings listed above are implemented. Editing requires re-entering the secret reference because it is intentionally not returned.

### Layers, patterns and components

- Web adapter: role checks, tenant extraction, HTTP DTOs, safe response projection.
- Application service: create/update orchestration, audit recording, store port.
- Domain: provider, secret-reference, endpoint and event validation; explicit configuration-only statuses.
- Mongo adapter: `oh_integration.integration_configs`, tenant/kind index, atomic version-filtered replacements.
- Patterns: ports and adapters, repository port, optimistic concurrency, tenant isolation, DTO projection. Shared audit infrastructure records configuration changes without credential values.
- Components: Spring MVC/Security, MongoDB, shared Kafka audit infrastructure, gateway TokenRelay/rate limits, React Query. Redis supports existing shared infrastructure; no provider token cache is implemented yet.

## Remaining Phase 8 work

1. Provider-side revocation where supported, key rotation procedures and provider sandbox certification. Current disconnect is local, not provider consent revocation.
2. Calendar connector migration/reconciliation, candidate invitations, meeting URL write-back, circuit breakers, provider-specific retry timing and distributed refresh serialization. Microsoft 365 currently means calendar integration, not all Microsoft products. E-sign, HRIS and job-board adapters from the broader plan are not implemented.
3. Receiver sandbox TLS/network tests, queue quotas/retention and full 24-hour fault-injection acceptance. Pagination and webhook history/replay UI are now implemented.
4. Enterprise SAML/SCIM live interoperability acceptance, optional SCIM Groups, plugin SPI, seat/storage quota enforcement and quota operations UI. The scoped Users implementation and integration request/admission budgets are implemented; see the enterprise checkpoint. MCP tools/approval are implemented, but real delegated OAuth client configuration and transport interoperability acceptance remain open.
5. Helm deployment acceptance, coordinated full-platform disaster recovery, audit hash-chain acceptance, production SLO/runbook upgrades, load/chaos/security acceptance. Encrypted Mongo backup/restore tooling and a local isolated fixture rehearsal now pass; tenant data and other infrastructure were not backed up or restored.
6. Configuration-create request idempotency, broader controller/UI workflow coverage and deployed acceptance. Resolve the separate integrity-worker module decision against the original plan. Tests cover domain validation, application conflicts, OAuth state/refresh logic, provider mock responses, token encryption, Mongo isolation/atomic consumption/disconnect races, queue deduplication/retry/replay/reconciliation, MCP authorization and explicit approval, and calendar/webhook UI states.

OAuth app registrations and approved redirect URIs are needed for live verification. Supply secrets through the chosen secret store, not chat or committed files. No production-ready connector claim is made until live provider checks pass. Phase 7's outstanding runtime acceptance remains separate and open.

## Manual deployment and review

From `backend`, with existing host MongoDB, Redis, Kafka and Keycloak available:

```sh
./run-backend.sh phase8
docker compose --project-name openhire --profile phase8 up -d --no-deps --build api-gateway
```

The first command starts only integration-hub; the second rebuilds the gateway to load its new routes. Neither command increases Docker memory. Service health is available at `http://localhost:8107/actuator/health`; normal API calls go through the authenticated gateway. Do not use a passing health endpoint as evidence of OAuth connectivity.

Review saving/editing both provider configurations, stale update rejection, cross-tenant denial and persistence after restart before accepting at runtime. No Phase 8 containers were deployed during these implementation slices. Stop for the overall Phase 8 review before starting the next phase.

### Offline verification

Verified on 2026-10-16: **71 integration-hub tests and 6 gateway tests passed**, with no failures or skipped tests; the integration run enabled the isolated Mongo tests. **All 165 console tests passed**, and console TypeScript checking passed. Helm lint passed; offline chart rendering with autoscaling and TLS ingress passed in this implementation slice. Compose Phase 8 configuration validation passed. Provider responses and webhook transport are mocked. Tests use isolated Mongo databases, not tenant data. No live provider certification, deployment, SAML/SCIM or disaster-recovery acceptance is implied by offline results. No Docker memory changes were made.

```sh
./gradlew :integration-hub:test --no-daemon --max-workers=1
OPENHIRE_RUN_MONGO_TESTS=true ./gradlew :integration-hub:test --rerun-tasks --no-daemon --max-workers=1
```

The second command uses local MongoDB and creates/drops only uniquely named `oh_phase8_test_*` databases. Provider calls and webhook sends are mocked in these tests. No live provider keys are needed. Existing tenant databases are not used or erased.

Provider references: [Google OAuth web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server), [Microsoft authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).
