# Phase 8 — enterprise identity, quotas and recovery

Implementation checkpoint: 2026-10-16. This adds a constrained SCIM Users profile, Keycloak SAML configuration/pre-linking, shared integration budgets, and encrypted Mongo backup/isolated restore tooling. It does not certify a live enterprise IdP or finish full-platform disaster recovery. Additional e-sign, HRIS and job-board providers require a user choice; none has been selected or represented as implemented.

## Enterprise setup

Use `SECURITY_MODE=oidc` and `IDENTITY_PROVIDER=keycloak`. Local mode explicitly refuses enterprise provisioning rather than returning simulated success. Use the existing Keycloak administration client with narrowly scoped permissions for users, federation and reading authentication flows. Never put its secret into source control.

The operator migration is `backend/scripts/configure-enterprise-identity.mjs`. Supply `SCIM_TENANT_ID` for an existing OpenHire organization. Without `--apply`, it only prints the intended changes. With `--apply`, supply a short-lived `KEYCLOAK_ADMIN_TOKEN`, `KEYCLOAK_BASE_URL`, and optional `KEYCLOAK_REALM`. No administration token or generated client secret is printed. Non-local Keycloak must use HTTPS.

The migration:

1. Creates or validates `openhire-prelinked-only`, a basic flow with exactly one REQUIRED `deny-access-authenticator`. It refuses incompatible existing flows.
2. Declares `openhire_scim_id` as an admin-only user-profile attribute. Existing organization ownership attributes must also remain admin-managed; the existing `configure-keycloak-profile.mjs` migration includes this attribute now.
3. Creates a confidential, service-account-only SCIM client bound to the selected tenant, with fixed organization and audience claims, no browser/password grants, and `scim.provision` scope. Incompatible existing clients are rejected.

Retrieve the client secret securely from Keycloak and store it in the provisioning system's secret store. Enable `scimEnabled` through the existing SSO-policy screen/API. Setup scripts were syntax/dry-run checked, not applied to the running realm in this change. A partially completed migration must be inspected before rerunning; do not replace a flow with weaker defaults.

## SAML business logic and endpoints

SAML assertions terminate at Keycloak, not at a new custom XML parser in OpenHire. All provider aliases are deterministically derived from the authenticated organization. Configuration requires a current X.509 certificate, HTTPS SSO URL, explicit issuer, signature validation and signed authentication requests. Trust-email and token storage are disabled. No JIT user creation or email-based linking is permitted: users must be pre-provisioned and explicitly linked to the tenant's provider.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/sso-config/saml` | Read provider configuration status and expected version; excludes certificate |
| PUT | `/api/v1/sso-config/saml` | Apply `{version, connection: {enabled, entityId, ssoUrl, signingCertificate}}` to Keycloak, then record state |
| POST | `/api/v1/sso-config/saml/links` | Bind `{memberId, subject}` to the tenant's provider after validating local and Keycloak ownership |

Only authenticated organization/super administrators in an active tenant can use these APIs; anonymous/local mode cannot bypass checks. `subject` is the exact persistent NameID issued by the enterprise IdP, not an email inferred by OpenHire. Existing users continue to use their server-controlled organization attribute and application role assignments. Unlinked identities hit the deny-first-login flow. The enterprise IdP must be configured with Keycloak's broker service-provider metadata and signing certificate. Keycloak's existing OIDC login then presents the configured SAML provider; the SPA does not handle SAML assertions.

Configuration writes are versioned and serialized. A server crash during `UPDATING` is not silently retried: compare the persisted configuration with Keycloak before an operator releases that record. A failed remote change may have succeeded externally; inspect before retrying. The UI distinguishes enabled policy from a verified provider connection. Certificate rollover requires uploading the replacement certificate before expiry and a live signed-assertion test; a single certificate is supported at this checkpoint.

Patterns/components: external directory port, Keycloak REST adapter, optimistic version checks, fail-closed ownership validation, explicit federation linking, Mongo configuration record, shared audit recorder. The identity service never assigns a tenant based on an incoming SAML claim.

## SCIM Users profile

Base URL: `/api/v1/scim/v2`. The gateway uses a dedicated stateless bearer-authentication chain for this path; browser CSRF protection on other endpoints remains enabled. Identity service requires a signed JWT with audience `openhire-scim`, scope `scim.provision`, and the enabled active tenant's `organization_id`. A regular browser session or anonymous local-mode request is not accepted.

| Method | Relative path | Behavior |
| --- | --- | --- |
| GET | `/ServiceProviderConfig` | Advertises supported PATCH/filter/ETag and unsupported bulk/password/sort features |
| GET | `/ResourceTypes` | Advertises only User provisioning |
| GET | `/Schemas`, `/Schemas/{schema-id}` | Discovery for the supported User profile |
| GET | `/Users` | Tenant list; `startIndex` starts at 1, `count` is 0–200; equality filters on userName or externalId |
| POST | `/Users` | Create a managed user with immutable email userName and stable externalId |
| GET | `/Users/{id}` | Tenant-owned user and weak ETag |
| PUT | `/Users/{id}` | Replace supported attributes; optional `If-Match` prevents stale changes |
| PATCH | `/Users/{id}` | SCIM PatchOp add/replace for active/displayName, with optimistic version protection |
| DELETE | `/Users/{id}` | Disable in Keycloak, terminate its sessions and retain a tombstone locally; no hard deletion |

Creation requires `userName`, `displayName`, `externalId`; `active` defaults true. The local SCIM id differs from the Keycloak user id. Retry a failed create by filtering for the same externalId and updating its returned id. Duplicate names/external IDs return 409, including tombstones; SCIM cannot adopt existing unmanaged members. Changing username/externalId requires an administrator migration rather than silent reassignment.

Keycloak creation uses a stable managed username and initially disables the user. Organization/managed-ID ownership is checked before reusing a previous remote create. It then updates enabled state and persists the local member. SCIM cannot set groups, roles or passwords; newly provisioned users have **no application roles** until an administrator assigns them through the existing audited member API. Existing roles are preserved. Deactivation logs out Keycloak sessions; already-issued JWTs remain valid until their configured expiry, so keep access-token lifetimes short and verify deployment revocation requirements separately.

Supported provisioning is intentionally narrower than a full SCIM directory. Groups, arbitrary complex filters, email/username changes, bulk operations and password changes are unsupported. Configure the provisioning client's attribute mappings accordingly. Interrupted `UPDATING` operations require manual reconciliation of `scim_users`, local membership and Keycloak before releasing the lock; no timed takeover is used that could overlap an uncertain external write. ERROR records may be retried with the desired PUT. Cross-system transactions cannot make a remote update and local audit commit atomic.

Patterns/components: managed-resource lifecycle, unique tenant/name and tenant/externalId constraints, idempotent remote creation, optimistic concurrency, tombstones, external directory port, MongoDB, Keycloak and audit events. These integration budgets do not replace the existing commercial/user-seat quota policies; atomic seat enforcement across invitations and SCIM remains a separate gap.

## Integration quotas

`IntegrationBudget` uses atomic Mongo updates shared across service replicas. Receipt hashes make retries of the same operation consume one admission per window. Missing Mongo fails closed; there is no in-memory fallback. Quota documents expire two days after their window ends. Limits must be 1–50,000, bounding each receipt document's size.

| Spring property under `openhire.integration.quotas` | Default | Window |
| --- | ---: | --- |
| `requests-per-minute` | 300 | UTC minute; authenticated connector/webhook/MCP requests |
| `calendar-changes-per-day` | 10,000 | UTC day; desired-state admissions |
| `webhook-deliveries-per-day` | 50,000 | UTC day; subscription/event admissions |
| `configurations-per-day` | 1,000 | UTC day; new configuration admissions |

HTTP overages return 429. Retries within a window reuse queue admission identity; attempted admissions may remain charged when a subsequent write fails. Kafka-originated overages use the existing failed-event/dead-letter handling after bounded retries; inspect/replay them after budget availability returns. No event is silently declared delivered because quota was exhausted. These are rate/admission budgets, not lifetime object-storage caps, per-provider commercial quotas or automated billing limits. Existing gateway rate limiting remains in place.

Compose forwards `INTEGRATION_REQUESTS_PER_MINUTE`, `INTEGRATION_CALENDAR_CHANGES_PER_DAY`, `INTEGRATION_WEBHOOK_DELIVERIES_PER_DAY`, and `INTEGRATION_CONFIGURATIONS_PER_DAY`. These operator-configured limits apply independently to each tenant; they do not expose tenant-admin controls to raise their own limits.

Concurrency test: 20 competing operations after one consumed slot against a limit of 3 admitted exactly two additional operations, while retry, other-tenant and next-day cases remained valid.

## Encrypted Mongo recovery tool

`backend/scripts/mongo-recovery.mjs` requires Node, `mongodump` and `mongorestore`. Provide a private mode-600 Mongo tools configuration file via `RECOVERY_MONGO_CONFIG` (URI/password remain out of command arguments). Set `RECOVERY_ENCRYPTION_KEY` to a base64 32-byte key from a secret manager; retain it separately from the backup. Use a least-privileged backup identity and a separate isolated restore-cluster identity. No default credentials are supplied.

```sh
node scripts/mongo-recovery.mjs backup /secure-backups/integration.oha oh_integration
node scripts/mongo-recovery.mjs verify /secure-backups/integration.oha
node scripts/mongo-recovery.mjs rehearse /secure-backups/integration.oha
node scripts/mongo-recovery.mjs rehearse /secure-backups/integration.oha --execute
```

- Backups stream one explicitly named application database into an AES-256-GCM envelope. Metadata is authenticated; an unsuccessful dump never publishes a completed backup. Existing backup files are never overwritten.
- Verification authenticates the complete archive with no database writes. Tampering and incorrect keys fail.
- Rehearsal defaults to dry-run. `--execute` first authenticates the archive, then restores only the named source namespace into a random `oh_restore_<uuid>` database. It never uses `--drop` or overwrites the application's database. Retain files on trusted private storage during verification/restore.
- The CLI leaves the isolated target for operator inspection, including partial targets after failures. It never promotes or deletes databases. Confirm collections, indexes, counts and application invariants before planning a separate authorized promotion.
- Source writes must be quiesced for an application-consistent single-database dump; this tool does not claim a cross-service point-in-time snapshot. For production replica sets, supplement it with the storage/provider's supported continuous backup strategy.

Verified locally: a fresh fixture database was dumped, encrypted, authenticated, restored to a fresh isolated namespace, and compared with the original. The test then removed only its generated fixture/restore databases and temporary files. Node tests also cover wrong keys, tampering, failed dumps, no-overwrite publication and restricted restore namespaces. No tenant/application database was backed up, modified or removed.

Full-platform recovery remains broader: Keycloak's database, Kafka broker state/offsets and replay policy, Redis session invalidation strategy, resume/object storage, encryption keys, configuration secrets, schema compatibility and audit-chain verification need a coordinated recovery plan and rehearsal. This tool does not claim those are covered, and does not alter Docker memory.

## Additional adapters — provider decision required

Google Calendar and Microsoft 365 are implemented. No e-sign, HRIS or job-board provider was named in the plan. Choose the actual providers and supported operations before implementing provider-specific OAuth, endpoints, mappings, signatures and retries. Implementing a generic placeholder and marking it as a working DocuSign/HRIS/job-board connector would not satisfy the requirement. No live keys are required for the upcoming adapter contract tests.

## Verification commands

Final checkpoint: 32 identity-service tests, 73 integration-hub tests (including the real isolated Mongo restore rehearsal), and 6 gateway tests passed with no skips. Three Node recovery safety tests, all 165 console tests, console TypeScript checking, migration syntax/dry-run checks and Compose validation passed. No services were deployed, live IdP configuration changed, or Docker memory increased.

```sh
OPENHIRE_RUN_MONGO_TESTS=true OPENHIRE_RUN_RECOVERY_TESTS=true ./gradlew :identity-service:test :integration-hub:test :api-gateway:test --no-daemon --max-workers=1
node --test scripts/mongo-recovery.test.mjs
```

The recovery test requires local Mongo plus Mongo database tools. It creates and drops only its own randomly named test databases. Identity/provider calls are mocked except the temporary local Mongo recovery fixture. Live SAML login, actual SCIM client interoperability, provider certification and production disaster-recovery acceptance are not implied by passing tests.

Protocol references: [SCIM protocol RFC 7644](https://www.rfc-editor.org/info/rfc7644/), [Keycloak SAML configuration](https://www.keycloak.org/docs/26.3.5/server_admin/index.html), [Keycloak deny-access authenticator constant](https://www.keycloak.org/docs-api/26.5.7/javadocs/constant-values.html#org.keycloak.authentication.authenticators.access.DenyAccessAuthenticatorFactory.PROVIDER_ID).
