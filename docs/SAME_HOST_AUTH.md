# Same-host OpenHire authentication (local) — 2026-10-24

## Current target and implementation

This replaces the earlier shared `auth.localhost` design. The hostname stays fixed throughout native
password login, OTP, forgot password, activation and logout. Paths may change:

| Portal | Entry | Visible authentication | Return |
|---|---|---|---|
| Platform | `http://admin.localhost/login` | `http://admin.localhost/auth/realms/openhire-staff/...` | `admin.localhost/tenants` |
| Codlogicx | `http://codlogicx.localhost/login` | `http://codlogicx.localhost/auth/realms/openhire-staff/...` | Role-specific dashboard on `codlogicx.localhost` |
| Other onboarded tenant | `http://<slug>.localhost/login` | Same hostname under `/auth/...` | Same tenant hostname |

The literal `/login` path does not stay fixed. Keycloak handles credentials using its OpenHire theme;
React does not collect/proxy passwords. Authorization code, PKCE, state, nonce and ID-token validation
are retained. There is no OAuth registration/realm chooser. External Google/Microsoft/SAML identity
providers necessarily navigate to those providers; the same-host promise is for native credentials,
TOTP and email recovery, not third-party login screens.

## How the local boundary works

- Nginx exposes only `/auth/realms/openhire-staff/` and `/auth/resources/` on portal hosts. Other `/auth/` paths return
  404, so `/auth/admin`, health and management are not published there. `auth.localhost` returns 404.
- Every proxied authentication request performs an internal `auth_request` to `/bff/portal-status`.
  That endpoint explicitly resolves the host through the identity registry; unknown/suspended tenants
  or lookup failures fail closed. `admin.localhost` is the configured platform exception. The check
  does not create a browser session or return tenant details. Browser cookies/bearer headers are not
  forwarded to this registry check.
- Nginx replaces Host/forwarded headers. Keycloak listens on `/auth`; its hostname is derived from
  trusted proxy headers. The direct admin port is loopback-only. Do not expose it externally.
- The gateway wraps its client-registration repository per reactive request context, using the validated
  portal origin for the authorization endpoint and expected ID-token issuer. Code exchange, refresh and
  userinfo still call the configured private URL, forwarding that same origin. BFF cookies stay host-only.
  ID-token decoder caches are bounded and keyed by issuer/client, not only the shared tenant client ID.
  Authorized clients are stored in the individual BFF WebSession, not globally by user/client ID.
- Access-token issuers are now `http://<portal>.localhost/auth/realms/openhire-staff`, not one global issuer.
  All APIs use the fixed **internal JWKS endpoint**, never issuer-driven discovery. They retain signature
  and timestamp checks and constrain issuer scheme, host suffix, slug, port, realm path, authorized client,
  platform role and tenant claim shape. The gateway additionally matches the signed issuer to the current
  portal host and rechecks tenant membership, tenant ID and roles. Services still scope data by verified
  tenant ID; the URL slug is not a replacement tenant ID.
- Identity sends both the activation administration call and its service-token request with the validated
  tenant origin. Keycloak-generated activation links consequently remain on the tenant host.

**Local-only:** `OPENHIRE_PORTALS_SAME_HOST_AUTH=true` is restricted by the validators to HTTP `.localhost`
hosts. This is not a production AWS ingress configuration. Dynamically resolving a hostname without these
proxy/registry boundaries permits host-header attacks. Production needs reviewed TLS/domain allowlists,
private Keycloak networking, appropriate trusted-proxy restrictions and signed issuer-to-tenant binding.
Do not deploy this local configuration publicly or disable issuer validation to work around failures.

## Safe one-time cutover (operator-run)

The assistant does not execute these steps or restart services. Existing Keycloak accounts/client secrets
must be retained. The old base Compose Keycloak had no database volume; never blindly recreate it.

1. Schedule local downtime. In Keycloak, clear **Realm settings > General > Frontend URL** on the staff
   realm (and candidate/master if previously overridden). A fixed old frontend URL defeats same-host
   links. Keep the OpenHire login theme selected on `openhire-platform` and `openhire-tenant`.
   Export realm configuration securely as an additional recovery copy. Do not commit exports/secrets.
2. From the backend root, preserve local H2 before recreation:

   ```bash
   bash scripts/setup/prepare-portal-keycloak.sh --apply
   ```

   For an ephemeral local dev-file H2 instance, the helper stops the source, snapshots `data/h2` into
   ignored restricted `backups/`, and copies it to `<project>_keycloak-h2-data` without overwriting an
   existing target. Success leaves the original source stopped; failure attempts to restore it. If the
   source already uses that persistent volume, copying is skipped. Keep a separate offline backup before
   upgrading its settings. External databases need their own backup procedure; this helper refuses them.
3. Recreate Keycloak using the new override (the database volume is preserved):

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.tenant-portals.yml \
     up -d --no-deps --force-recreate --wait --wait-timeout 300 keycloak
   ```

   Administration is now `http://localhost:8180/auth/admin`. Health also moves under `/auth/health/ready`
   on the internal management port; the portal override updates that health check. Existing imports do
   not update clients or users in populated realms; no realm deletion/reimport is needed.
   Keycloak's fixed `hostname` and `hostname-admin` settings are both cleared: this Keycloak version
   does not permit a fixed admin hostname together with a dynamic frontend hostname. The admin console
   derives its operator-only address from the direct loopback request and is not exposed by Nginx.
4. Rebuild and redeploy **all 18 applications**, since shared JWT validation and the internal Keycloak path
   changed. Existing images from the shared-auth implementation are not sufficient:

   ```bash
   ./run-backend.sh deploy --tenant-portals --follow
   # Retain the explicit local trusted-fixture scanner bypass, if applicable:
   ./run-backend.sh deploy --tenant-portals --no-scan --follow
   ```

   Use only one of these commands. `--tenant-portals` refuses old hostname/path/storage configurations.
   Deployment reinstalls the OpenHire theme and recreates Nginx after the applications are ready. Use
   `--tenant-portals` on later deployments too; do not mix base and same-host issuer settings. For a
   custom Compose project, consistently use `OPENHIRE_DOCKER_PROJECT` and `--project-name` manually.
5. Keep callbacks operator-managed and exact. No wildcard callbacks or client-management privileges:

   - Platform callback: `http://admin.localhost/login/oauth2/code/openhire-platform`.
   - Tenant callback: `http://codlogicx.localhost/login/oauth2/code/openhire-tenant` (repeat per tenant).
   - Logout redirects remain each portal's `/login`.
   - Tenant activation redirect remains its `/login` in valid redirects.
   - Leave the shared tenant client's Root URL/Home URL unset; a single tenant-specific home URL would
     send other tenants to that tenant on Keycloak's informational/back-to-application screens.

6. Onboard the tenant before testing its authentication. Sign out/clear only the affected portal's old
   browser session, start a fresh `/login`, and request fresh activation/reset emails. Old links/tokens
   contain old paths/issuers. Do not flush Redis or delete users. Console security links now use the current
   portal's `/auth/realms/openhire-staff/account/`; remove obsolete account-URL overrides if set.
   Start frontend dev servers from the frontend repository with `OPENHIRE_TENANT_PORTALS=true pnpm dev:all`
   so the role dashboards are available at the proxy's configured frontend ports.

Never use `down -v`. Do not overwrite a partially populated migration target after a failed copy; inspect
the preserved source and backup first. Rolling back needs coordinated hostname, internal-path and token
validation settings for all services; merely dropping the override is not a safe rollback.

## Acceptance checklist

### Public processing consent tenant resolution

`GET /api/v1/public/consent` is public and its route deliberately does not relay a BFF token.
Application-service needs a tenant to select the policy; a tenant hostname alone is not
visible to its consent controller. The portal boundary therefore replaces/adds the
`tenantId` query with the active hostname registry's tenant ID for this exact GET endpoint.
This supports anonymous candidates and authenticated recruiters without static tenant
configuration or relying on a browser tenant header. A conflicting browser query is denied,
unknown/suspended tenants fail closed, and the platform host cannot choose a tenant's policy.
Other requests are unchanged. The registry/authorization checks are not disabled.

Deploy the gateway change and reload the affected page:

```bash
./run-backend.sh deploy --tenant-portals --no-scan api-gateway --follow
```

Five focused boundary checks cover dynamic anonymous resolution, spoofed-header removal,
matching/conflicting queries, inactive tenant failures, the platform host and unchanged
unrelated queries. Live acceptance should confirm the consent GET returns a version and text
before uploading a candidate; it does not itself record a candidate's agreement.

### Tenant job creation and CORS (gateway forwarding)

The job route preserves the public Host header when proxying to its fixed private backend
address. Without `PreserveHostHeader`, a browser POST from `http://<slug>.localhost` reaches
Spring MVC with an internal service hostname. Job-service's legacy CORS mapping then treats
the request as cross-origin and rejects the tenant origin. Browser GET requests often omit
Origin, so listing jobs can succeed while creation fails.

This is not fixed by granting more roles, adding wildcard CORS or disabling CSRF. Portal
host/membership validation, token relay, rate limiting and CSRF remain in place; the upstream
network target remains `JOB_SERVICE_URL`, not the browser-supplied host. Explicit legacy
development origins continue working. Production TLS termination additionally needs reviewed
scheme/forwarded-header handling; this local HTTP fix is not an AWS ingress configuration.

Deploy the gateway change using the prepared portal configuration:

```bash
./run-backend.sh deploy --tenant-portals --no-scan api-gateway --follow
```

After signing in afresh, review GET and POST `/api/v1/jobs`. A complete valid creation must
return 201; a validation failure is not a successful creation. Isolated checks reproduce the
old CORS 403, allow same-origin requests with the preserved host for two tenant names, reject
an unrelated origin and retain the explicit local development allowlist. No live job was
created by these checks. Never paste session cookies into troubleshooting messages; sign out
and sign in again after a cookie has been shared.

### Keycloak operator checklist (existing realms; do not delete/reimport)

Use the `openhire-staff` realm, not `master` or `openhire-candidate`. Before cutover the
admin console is `http://localhost:8180/admin`; afterwards it is
`http://localhost:8180/auth/admin`. Keycloak administrators and application super-admins
are different accounts. Keep an operator session available while verifying the changes.

1. **Realm settings > General:** remove a fixed Frontend URL if present. Under **Themes**,
   select login theme `openhire` after the deployment installs it. If a client overrides
   the login theme, select `openhire` there too. Changing realm display name alone does
   not install or select the theme.
2. **Clients:** create the following OpenID Connect clients if absent; otherwise edit them.
   Both belong to `openhire-staff`:

   | Setting | `openhire-platform` | `openhire-tenant` |
   |---|---|---|
   | Enabled / Client authentication | On / On | On / On |
   | Standard flow | On | On |
   | Implicit flow / Direct access grants / Service accounts | Off / Off / Off | Off / Off / Off |
   | PKCE code challenge method | S256 | S256 |
   | Valid redirect URIs | `http://admin.localhost/login/oauth2/code/openhire-platform` | `http://codlogicx.localhost/login/oauth2/code/openhire-tenant` |
   | Additional valid redirect for activation | Not needed unless platform invitations use it | `http://codlogicx.localhost/login` |
   | Valid post logout redirect URIs | `http://admin.localhost/login` | `http://codlogicx.localhost/login` |
   | Root URL / Home URL | Leave empty | Leave empty |

   Repeat the two tenant redirects and logout URL for **each onboarded tenant**; do not
   replace another tenant's existing entries. Never use `*` to avoid operator registration.
   PKCE may be in **Settings > Capability config** rather than **Advanced**, depending on
   the console version. Its persisted client attribute is `pkce.code.challenge.method=S256`.
   Browser token calls are not used: this is a confidential BFF client, not a public React
   client. Do not add wildcard Web Origins; native BFF login does not require browser CORS
   access to Keycloak's token endpoint.
3. **Credentials:** securely copy each client's existing secret to the backend's ignored
   local `.env`: `PLATFORM_OIDC_CLIENT_SECRET` and `TENANT_OIDC_CLIENT_SECRET`, respectively.
   Compose passes these to `api-gateway`. Do not put them in `VITE_*`, React, Git or this
   document. Do not regenerate a secret merely to view it. Rotate previously shared secrets
   deliberately, updating the client and gateway together.
4. **Client scopes / dedicated scope:** ensure `openid profile email` is supported and the
   realm-role mapper emits `realm_access.roles` in the **ID token and access token**, and
   UserInfo when used. The default `roles` scope alone may not put roles in the ID token;
   inspect its mapper. Reuse the existing role mapper where possible instead of adding
   duplicate claim writers. Platform users need assigned `SUPER_ADMIN`; tenant users must
   not receive that role.
5. **Tenant claim:** the signed single-string tenant identifier must match the identity
   registry and membership, not a guessed slug. Readers support canonical `tenant_id` and
   transitional `organization_id`; if both are emitted they must agree. Existing identity
   provisioning still has legacy attribute compatibility: preserve its current mapper
   until the separately rehearsed schema/claim migration is applied. Do not rename only
   the mapper while provisioning still writes the old attribute. Keep either attribute
   administrator-managed, never user-editable through profile registration/account UI.
6. **Users:** verify the intended application user is enabled, has the appropriate realm
   role and tenant membership, and a usable credential or invitation. Do not assign
   `SUPER_ADMIN` to a tenant administrator. A successful password check does not override
   inactive tenant status or missing application membership.
7. **OTP:** the OpenHire OTP template changes appearance, not authentication policy.
   Keep the browser flow's OTP execution enabled; use **Configure OTP** as a required
   action when enrollment is required. An enrolled authenticator produces the six-digit
   code. Do not introduce a dummy code or promise that every account gets OTP without an
   enforced flow. Backup-code/trusted-device controls require corresponding configured
   authenticators; UI appearance alone is not implementation.
8. **Recovery/email:** enable **Forgot password** in realm login settings. Configure realm
   **Email** with a working SMTP sender and test delivery. For local Mailpit, the usual
   internal SMTP address is `mailpit:1025` (no TLS/auth only on the isolated local network);
   use the actual Compose service configuration. Verify email only succeeds with delivery
   configured. For local seeded fixtures only, an operator may mark their existing email
   verified; do not disable production verification or use a fake verification code.
9. **Identity service account:** retain the existing `openhire-service` account and its
   required user-provisioning permissions. Do not enable service accounts on the two browser
   clients or grant client-management privileges to automate callback registration.

Client settings are stored in Keycloak's database; a container restart does not create
missing clients or overwrite an existing realm from import JSON. Server hostname/path
settings, gateway configuration, images and proxy changes **do** require the coordinated
cutover above. Restarting only the frontend cannot remove Spring's selector page.

### Diagnose before retrying the browser

From the backend root, this read-only gate checks Keycloak's server mode and persistent storage:

```bash
bash scripts/setup/check-portal-keycloak.sh
```

If it reports cutover required, follow the backup/recreation sequence above; do not repeatedly
retry login. After all applications and the proxy are deployed in portal mode, inspect only
the first redirect (do not publish OAuth state/codes, cookies or full callback URLs):

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://admin.localhost/login
```

Expect an authentication redirect, not a 200 response containing the default OAuth selector.
In the browser, the redirect chain must stay on `admin.localhost` and end at an OpenHire form
under `/auth/realms/openhire-staff/`. A redirect to `localhost:8180` means old hostname,
realm Frontend URL, or gateway/provider configuration remains active. `invalid_client`
requires checking the exact client ID, client authentication and matching gateway secret;
`Invalid parameter: redirect_uri` requires checking the exact callback entry.

### Browser acceptance (perform in this order)

- Platform `/login` must not show “Login with OAuth 2.0”. The branded form and OTP stay on `admin.localhost`.
- Codlogicx login, OTP, reset email and activation email stay on `codlogicx.localhost`; role landing works.
- Unknown/suspended tenant auth paths fail; tenant A's session/token cannot access tenant B's portal.
- Invalid signature, expired token, wrong realm/client, legacy/shared-host issuer and reserved host fail.
- Logout clears the BFF session; retry from a fresh login. Native Keycloak cookies are host-only.
- No browser request uses `localhost:8180` or `auth.localhost` during the normal end-user flow.

Source/API compilation and fixture checks are not browser acceptance. Live Keycloak cutover and complete
login/OTP/recovery still require owner execution. No live users, database state or client settings are
modified by the code changes alone.

## Validation completed

- Targeted Java source/API compilation into temporary directories succeeded.
- 16 targeted authentication checks passed: per-host registrations and PKCE; Spring component wiring;
  pinned backchannel URLs/headers; ID-token cache and browser-session client isolation; active/unknown/
  suspended tenant checks; JWT issuer/client/role/tenant rules, real signature and expiration rejection;
  and same-origin service-token/activation requests.
- All 40 Node pipeline/configuration/script checks passed, including safe storage-copy mock scenarios.
- Nginx configuration syntax passed inside the existing proxy without reloading it.
- Merged normal/local-no-scan portal Compose configuration passed; all 18 services enable same-host auth.
- Dependency locks resolved successfully offline across all 20 backend modules (18 services plus libraries).
- Console TypeScript checking, package layout, endpoint reference (314 operations) and diff checks passed.

No application JAR/image build, live Keycloak migration, database write, user/password change, proxy reload
or service restart was performed. These checks do not establish end-to-end browser acceptance.
