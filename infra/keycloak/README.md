# Keycloak Phase 1 realms

Import both JSON files at Keycloak startup. Replace every `replace-before-use` client secret and provide the matching values through `STAFF_OIDC_CLIENT_SECRET`, `CANDIDATE_OIDC_CLIENT_SECRET`, and `KEYCLOAK_ADMIN_CLIENT_SECRET`.

The browser clients require Authorization Code + PKCE (`S256`) and disable direct password grants. The BFF exposes these login entry points:

- `/oauth2/authorization/openhire-staff`
- `/oauth2/authorization/openhire-candidate`

For production, replace localhost redirect origins, configure SMTP, enable required actions for verify-email/TOTP as dictated by tenant policy, and store the service credential in the deployment secret manager.
