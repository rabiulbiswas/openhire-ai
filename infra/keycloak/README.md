# Keycloak Phase 1 realms

For the current same-host tenant/platform portals, use [SAME_HOST_AUTH.md](../../docs/SAME_HOST_AUTH.md).
The older Phase 1 registration URLs below are retained for legacy deployments only.

## Local invitation and password-reset emails

Tenant admin activation and password-reset emails are sent by **Keycloak**, not by the
compliance audit consumer or notification-dispatcher. Configure `openhire-staff` under
**Realm settings > Email** with the existing local Mailpit service:

| Field | Local value |
|---|---|
| Host | `mailpit` |
| Port | `1025` |
| From | `no-reply@openhire.local` |
| From display name | `OpenHire AI` |
| Authentication / SSL / StartTLS | Off (isolated local development only) |

Mail is captured at <http://localhost:8025>; Mailpit does **not** deliver to external inboxes.
No Keycloak restart is required for saved realm SMTP settings. Production requires a real
SMTP provider and appropriate authentication/TLS; never expose unauthenticated Mailpit publicly.

After fixing SMTP, retry the invitation from the **existing tenant's details** rather than
creating the tenant again. Existing exact tenant `/login` activation redirects must be registered
on `openhire-tenant`. Test recovery separately with a fresh forgot-password flow. A successful
SMTP connection/configuration check is not proof that a particular invitation was delivered.

An `audit.events.v1-dlt=UNKNOWN_TOPIC_OR_PARTITION` warning concerns Kafka recovery, not SMTP.
If the topic now exists with a leader, inspect the original consumer exception before replaying.
The identity producer now sends domain/audit envelopes as JSON strings through StringSerializer
exactly once. Compliance accepts one legacy JSON-string wrapper for retained audit records;
malformed or repeatedly wrapped records still fail and remain recoverable. Deploy both identity
and compliance changes before an operator reviews/replays an old audit dead letter. Do not delete
the topic, reset consumer offsets or blindly replay all events to silence the warning.

To deploy only this serialization correction after the full same-host cutover is complete:

```bash
./run-backend.sh deploy --tenant-portals --no-scan identity-service compliance-service --follow
```

The fix was checked with four isolated cases: raw audit JSON stays an object on the wire;
identity domain envelopes are encoded once; one historical string layer is accepted; invalid
and multiply wrapped messages are rejected. No automatic dead-letter replay is performed.

## Identity provisioning 403: realm role enumeration

A stack trace ending at `KeycloakIdentityProvider.updateMemberRoles` on a GET to
`/admin/realms/openhire-staff/roles` is not an SMTP error. The realm-wide role-list endpoint
can reject the service account even when it already has permission to manage users.
The provider now reads a user's assigned roles and `/role-mappings/realm/available`,
combines those results for the requested allowlisted tenant roles, and fails before any
role removal when a requested role is absent or unassignable. Only replaced tenant roles
are removed; default and unrelated roles remain untouched.

Do not grant `realm-admin`, `manage-realm` or `manage-clients` merely to fix this lookup.
No new service-account privileges are required for this change. Callback registration
remains operator-managed. User management and permitted role assignment must still be
configured in Keycloak; the application does not bypass their authorization checks.

After updating the identity image, retry the invitation from the existing tenant details:

```bash
./run-backend.sh deploy --tenant-portals --no-scan identity-service --follow
```

The recovery lookup can reuse a provider user created before role assignment failed;
no user deletion or duplicate tenant creation is necessary. Activation-email delivery is
a later step and must be checked separately in Mailpit. Four focused role-mapping checks
cover new assignment/removal, idempotent existing roles, missing roles and rejection of
platform/arbitrary roles without contacting Keycloak.

## Legacy Phase 1 setup

Import both JSON files at Keycloak startup. Replace every `replace-before-use` client secret and provide the matching values through `STAFF_OIDC_CLIENT_SECRET`, `CANDIDATE_OIDC_CLIENT_SECRET`, and `KEYCLOAK_ADMIN_CLIENT_SECRET`.

The browser clients require Authorization Code + PKCE (`S256`) and disable direct password grants. The BFF exposes these login entry points:

- `/oauth2/authorization/openhire-staff`
- `/oauth2/authorization/openhire-candidate`

For production, replace localhost redirect origins, configure SMTP, enable required actions for verify-email/TOTP as dictated by tenant policy, and store the service credential in the deployment secret manager.
