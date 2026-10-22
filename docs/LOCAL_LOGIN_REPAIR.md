# Local login repair

Source changes fix exact frontend callback allowlists (including auth port 5176 and platform port 5175), super-admin/candidate session roles and role-to-portal landing. Saved API requests no longer override the portal destination. Swagger-initiated staff login retains its dedicated return path.

## Apply to existing Keycloak without deleting realms

Updating an import JSON does not update an already-created realm. From the backend root, with Node 22+ and local Keycloak running:

```bash
# zsh: enter your existing local admin credential without putting it in history
read -s 'KEYCLOAK_ADMIN_PASSWORD?Local Keycloak admin password: '; echo
export KEYCLOAK_ADMIN_PASSWORD
node scripts/setup/repair-local-login.mjs --apply

# Optional: create the role-specific demo logins
read -s 'OPENHIRE_DEMO_PASSWORD?Demo password (12+ characters, upper/lower/digit/symbol): '; echo
export OPENHIRE_DEMO_PASSWORD
node scripts/setup/seed-ui-users.mjs --apply
unset KEYCLOAK_ADMIN_PASSWORD OPENHIRE_DEMO_PASSWORD
```

The migration is loopback-only. It preserves accounts, passwords, unrelated client settings and existing callback entries. It adds exact localhost callbacks, the basic OIDC scope and organization/role claims; it makes organization profile attributes admin-managed. Do not point it at production forwarded to localhost. A partial failure can be rerun safely; inspect the reported realm/operation.

Rebuild/recreate `api-gateway` using your normal backend deployment workflow and rebuild the frontend apps. No Java build, container restart, or live migration was performed while implementing this repair. Start a fresh browser session afterward so old authentication requests do not retain an invalid callback.

## Landing destinations

- Staff: port 5173; existing console router chooses the home for the active role.
- SUPER_ADMIN: port 5175 `/organizations` (platform permissions are included in the session).
- AGENCY_RECRUITER: port 5174 `/jobs`.
- Candidate registration: port 3000 `/portal`; no staff permissions are granted.

Backend properties `openhire.ui.staff-url`, `openhire.ui.partner-url`, `openhire.ui.platform-url` and `openhire.ui.candidate-url` control destinations outside localhost. Candidate and staff remain separate realms; user migration/realm consolidation is not performed. Candidate receipt-based workflows still need an application receipt; login alone does not populate applications or employee/agency business records.

## Remaining independent work

- React-to-Keycloak theme packaging/install is still required to replace the provider's default visual appearance. Callback repairs do not install a theme.
- Public company registration and anonymous invitation CSRF alignment remain separate implementation items documented in the frontend Phase 0–1 checkpoint.
- Live acceptance: staff, super-admin, agency and candidate login; logout; invalid password; role boundaries; candidate cannot access staff docs; callback rejection for unregistered origins. New tests remain deferred at the owner's request.
