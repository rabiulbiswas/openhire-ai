# Tenant portals and Super Admin onboarding

**2026-10-23 expansion:** canonical `/api/v1/platform/tenants` APIs coexist with the legacy organization paths.
The platform UI uses `/tenants`. Claim readers prefer `tenant_id` and reject conflicting legacy claims. See
[tenant naming migration](TENANT_NAMING_MIGRATION.md) for the passed synthetic Mongo rehearsal, shared
tenant-scoped collection strategy, and remaining repository/Keycloak cutover work. Live persistence is unchanged.

## Agreed target

- Independent platform portal: `admin.localhost` locally; production domain undecided.
- One portal and one login per tenant, such as `codlogicx.localhost`.
- Super Admin onboards organisations, invites their initial org admin, monitors tenant details and controls status.
- Org admins invite their organisation's users and assign tenant roles. They cannot grant platform Super Admin.
- All tenant roles ultimately use the same branded username/password/recovery experience; no realm selector.
- Password authentication remains owned by the identity provider. OAuth browser redirects may happen internally;
  passwords must not be collected by React and proxied using a password-grant shortcut.
- Business plans, subscriptions, payments and plan-based entitlements are deferred. Existing quotas are operational
  limits, not billing plans. No paid plan is assigned during onboarding.

## Callback registration decision

Callback registration is **operator-managed**. The identity service must not be granted Keycloak
`manage-clients`, `realm-admin`, or equivalent client-configuration privileges for tenant onboarding.
`configureTenantLogin` validates the configured origin only; it does not register it or verify Keycloak's
allowlists. A successful origin validation is not proof that the tenant is ready to log in.

A Keycloak operator must configure the confidential `openhire-tenant` client in `openhire-staff` before
sending a new tenant's activation email. For the local Codlogicx example, add these exact entries while
preserving entries belonging to existing tenants:

| Client setting | Value |
| --- | --- |
| Valid redirect URIs | `http://codlogicx.localhost/login/oauth2/code/openhire-tenant` |
| Additional valid redirect URI for password-setup email | `http://codlogicx.localhost/login` |
| Valid post logout redirect URIs | `http://codlogicx.localhost/login` |

Use a separate confidential `openhire-platform` client for the independent platform portal, with
`http://admin.localhost/login/oauth2/code/openhire-platform` as its callback and
`http://admin.localhost/login` as its post-logout redirect. Enable the authorization-code (standard)
flow; do not enable password/direct-access grants as a shortcut. Configure the matching client secrets
in the gateway environment, never in frontend code. Browser-to-Keycloak CORS is not required for the
server-side code exchange, so do not add wildcard web origins. Production URLs must use HTTPS.

Do not use wildcard callbacks or give the identity service client-management privileges to avoid this
operator step. Configure realm email delivery and the tenant role catalog separately. Registration does
not create DNS/hosts entries, deploy the proxy/theme, or verify the complete authentication workflow.

### Onboarding order and recovery

1. Choose the tenant slug and have an operator register its exact URLs.
2. Configure local host routing (or production DNS/TLS) and verify the authentication client configuration.
3. Super Admin creates the tenant and requests the initial org-admin invitation.
4. With tenant mode enabled, the identity service provisions the account and asks Keycloak to email
   `VERIFY_EMAIL` and `UPDATE_PASSWORD` actions, valid for 24 hours. No password is emailed.
5. If email delivery or callback validation fails after the tenant was created, fix the operator settings
   and resend the initial admin invitation on the **existing** organisation. Do not recreate/delete it.

These operations are not atomic. A failed email can leave an INVITED member and a pending invitation;
the operator must verify successful delivery rather than treating a created organisation as fully onboarded.

## Onboarding implementation

The existing platform application remains on port 5175. Its new-organisation form now asks for the initial admin
email and calls the onboarding endpoint. Organisation detail panels refresh every 30 seconds with total users,
active users, active administrators and pending unexpired invitations. Existing status and quota controls remain.
No candidate records, credentials or invitation hashes are returned by the overview endpoint.

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `POST /api/v1/platform/organizations/onboard` | SUPER_ADMIN | Accept `{name, slug, adminEmail}`; create organisation and return `{organization, adminInvitation}` |
| `GET /api/v1/platform/organizations/{id}` | SUPER_ADMIN | Organisation metadata and user/invitation counts; business-plan status explicitly not implemented |
| `POST /api/v1/platform/organizations/{id}/admin-invitations` | SUPER_ADMIN | Accept `{email}`; issue an ORG_ADMIN invitation for an active existing tenant |

The existing create/list/status/quota endpoints remain compatible. Infrastructure slugs (`admin`, `auth`, `api`,
`www`, `mail`, `static`, `assets`, `support`, `localhost`) are reserved for new tenant creation. Existing slugs are
not renamed. Tenant role assignment rejects SUPER_ADMIN, including invitation acceptance. Invitations cannot be
accepted for suspended organisations.

Invitation responses use `Cache-Control: no-store`. In legacy mode, raw tokens are displayed temporarily to the
platform operator for secure delivery and are not stored in frontend local storage. With tenant mode enabled,
the backend instead requests the Keycloak activation email and returns an empty raw-token field. Frontend
invitation screens distinguish email requests from legacy manual tokens. No password is generated or returned.
Tokens are bearer secrets: do not include them in logs, screenshots or analytics.

### Failure and recovery

Organisation creation and invitation issuance span multiple persistence/provider operations and are not atomic.
If onboarding fails after creation, the UI refreshes the organisation list. Select the existing tenant and use the
admin-invitation action; do not recreate it or delete it. Duplicate slugs are rejected by the existing unique index.
This increment does not implement an idempotent provisioning saga or automatic rollback.

## Source implementation and boundaries

- Opt-in `docker-compose.tenant-portals.yml` and `--tenant-portals` deployment flag; proxy listens on local
  loopback port 80. Console, partner, candidate and platform remain separately runnable frontend applications.
- Gateway resolves the hostname through identity service and checks active organisation, membership, exact
  organisation claim, supported roles and authentication client. Unknown/suspended tenants fail closed.
  Caller-supplied tenant/actor/internal-secret headers are removed. A conflicting `tenantId` query is rejected.
- Platform login requires SUPER_ADMIN; platform sessions cannot call tenant business APIs. Tenant invitations
  and role changes cannot assign SUPER_ADMIN. Supported role catalog is seeded for tenant mode.
- Code flow uses PKCE. Backend keeps tokens; cookie domain is not widened to share sessions across tenants.
  Login callback checks membership before choosing a dashboard; logout stays on the validated host and remains
  available to revoked users. Legacy invitation/verification completion URLs are disabled at tenant hosts.
- First verified login activates an invited member and closes their pending invitation records. Invitation
  retry can recover provider-success/database-failure cases without creating a second user for the same tenant.
- React uses the single same-host `/login` entry on `.localhost`, supports role preferences drawn only from
  verified session roles, and routes partner/candidate views under `/partner` and `/candidate`.

This is **not a completed production security acceptance**. Do not expose backend service ports directly to
untrusted clients: the new host boundary is at the gateway. Independent integration credentials, public
receipt/scheduling/offer capabilities and existing SCIM paths require separate acceptance; host checks do not
replace those endpoints' existing resource authorization. Existing candidate identities are not migrated from
the old realm, and a shared realm currently rejects reusing one email across different organisations. Custom
role definitions are not automatically converted into Keycloak business roles. Business plans remain deferred.

## Local operator setup

1. Use `admin.localhost` and `<tenant>.localhost`. Modern browsers resolve `.localhost` to loopback without
   hosts-file edits. The proxy must still run on port 80. This local HTTP configuration is for synthetic data
   only; production requires DNS, TLS and a separately reviewed trusted-proxy configuration. If a system tool
   does not resolve `.localhost`, test the proxy with `curl --resolve admin.localhost:80:127.0.0.1 http://admin.localhost/login`.
2. In Keycloak's `openhire-staff` realm, create the two confidential clients described above. Use standard flow,
   PKCE S256, no implicit/direct-access grants and no service accounts for these browser-login clients. Set
   `TENANT_OIDC_CLIENT_SECRET` and `PLATFORM_OIDC_CLIENT_SECRET` to their generated secrets in backend `.env`.
3. Ensure client scopes expose `realm_access.roles` in the ID token, access token and userinfo, and map the
   user's `organization_id` attribute to a single string claim with that exact name. Retain the audience setup
   required by your existing resource servers. Make `organization_id` administrator-managed, not editable by
   users. Do not enable user self-registration or default staff/administrator roles.
4. Ensure realm roles exist: ORG_ADMIN, RECRUITER, HR_ADMIN, HIRING_MANAGER, INTERVIEWER, COMPLIANCE_OFFICER,
   EMPLOYEE, AGENCY_RECRUITER, CANDIDATE and SUPER_ADMIN. Only the operator grants SUPER_ADMIN to the separate
   platform account. Keep identity-service user-management permissions; do not grant client-management access.
5. Configure realm SMTP (local Mailpit: host `mailpit`, port `1025`, no TLS/auth, a local sender address), enable
   Forgot password, and confirm VERIFY_EMAIL and UPDATE_PASSWORD actions are enabled. Use real SMTP/TLS outside
   local development. Mailpit captures mail; it does not deliver it to real external inboxes.
6. Run `bash scripts/setup/install-login-theme.sh` to copy the OpenHire assets into the **existing** Keycloak
   container without restarting it or modifying users/clients. In `openhire-staff`, select each client
   (`openhire-platform` and `openhire-tenant`) > Settings > Login settings > Login theme > `openhire` > Save.
   Client-specific selection avoids changing the Keycloak admin console. The native form supports username,
   password, reset-password, MFA and activation screens; React never receives passwords. The template inherits
   Keycloak's accessible controls and security behavior, with OpenHire styling and copy.
   Tenant deployment also installs these assets automatically, but theme selection remains operator-managed.
   Do not recreate a non-persisted Keycloak container to install a theme. The provider URL can still contain its
   realm path; branding removes the chooser/branding, not the OAuth redirect protocol. Keeping the browser on
   the exact portal hostname throughout is not implemented by this theme.
   See `infra/keycloak/themes/openhire/README.md` for mockup styling, OTP behavior and supported controls.
7. Start the frontend applications from the frontend repository:

   ```bash
   OPENHIRE_TENANT_PORTALS=true pnpm dev:all
   ```

   Console uses 5173, partner 5174, platform 5175 and candidate 3000. The standalone auth app is not used by
   tenant-host login. The proxy reaches frontend dev servers via `host.docker.internal`.
8. After reviewing/building the Java changes, deploy the selected backend services and proxy:

   ```bash
   ./scripts/deployment/deploy-local.sh --tenant-portals identity-service api-gateway
   ```

   Other business services must already run. Add `--no-scan resume-processor` only when intentionally using
   the trusted synthetic-resume override. Reuse `--tenant-portals` on later deployments; omitting it restores
   the base Compose configuration for selected services. This flag does not configure Keycloak.
9. Open `http://admin.localhost/login`, onboard Codlogicx and open the activation email in Mailpit. Then use
   `http://codlogicx.localhost/login`. Do not use an ORG_ADMIN account to sign in to the platform portal.

## Verification status

### If `/login` displays “Login with OAuth 2.0”

That is Spring Security's generated selector, not the themed password form. A running gateway without
`OPENHIRE_PORTALS_ENABLED=true` still uses that legacy flow. Starting only `tenant-proxy` does **not** enable
tenant mode on an already-running gateway. Deploy identity/gateway with `--tenant-portals` using the current
images/source. For already-built current images, `--no-build --tenant-portals identity-service api-gateway`
recreates those services with the correct settings. Always keep the override on subsequent deployments.

After activation, `/login` should redirect automatically to the correct client, without offering a selector.
After client theme selection, the resulting password page must show OpenHire branding. Start a fresh login
after changes; do not reuse an old callback URL or authentication error page. No default/shared passwords or
password grants are introduced by this design.

Frontend auth/console/platform/partner/candidate TypeScript checks passed, as did the five existing mocked
deployment-script checks and shell syntax validation. No Java build, live Keycloak changes, database migration,
service restart or end-to-end browser acceptance was performed. Recheck these after deployment. The new
tenant-specific branch still needs runtime verification; existing script checks are not tenant security tests.

## Local validation once the remaining work is implemented

Onboard Codlogicx from the independent Super Admin portal; activate its admin through the emailed invitation;
invite recruiter, compliance and candidate users; verify role-specific dashboards on the same tenant host;
attempt cross-tenant access; verify tenant users cannot access platform APIs; suspend/reactivate a tenant;
check logout, password reset, existing sessions and multiple-role accounts. Business-plan controls remain absent.
