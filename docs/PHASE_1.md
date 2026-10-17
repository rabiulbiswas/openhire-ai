# Phase 1 — identity, tenancy and gateway

## Delivery status

Phase 1 implementation is complete and runtime-accepted on 2026-10-09: `scripts/testing/verify-phase1-runtime.sh`
passes against a freshly rebuilt stack, and the live browser spec `apps/console/e2e-live/phase1-session.spec.ts`
proves sign-out ends both the BFF session and the Keycloak session (5 consecutive passes).

API-key validation was failing in the running stack (valid keys rejected with 401) because the
identity-service container was running a superseded build: its image tag had been rebuilt after the
container started, without recreating the container. Rebuilding and force-recreating every service from
current source fixed it; the source itself was correct. After any backend change, redeploy with
`docker compose up -d --force-recreate <services>` rather than relying on image timestamps.

Still owner-run, because they need an authenticator app or a mailbox: TOTP enrollment, email verification and
password-reset emails.

## Implemented scope

- Staff and candidate OIDC realms with PKCE, email verification, reset-password, TOTP and brute-force protection.
- Redis-backed BFF session cookie; browser code never receives access or refresh tokens.
- Automatic authenticated redirect to `/recruiter/today` after verification.
- Logout revokes the application session and provider session, then returns to sign-in.
- Tenant propagation, tenant-scoped persistence and gateway rate limiting.
- Organizations, users, roles, permissions, invitations, API keys, SSO policy, setup progress, quotas and break-glass requests.
- Account profile and browser-session management.
- Local startup seed for `demo-org`, `ORG_ADMIN`, `RECRUITER`, admin and recruiter records.
- Keycloak role conversion and method-level RBAC.
- Identity OpenAPI and AsyncAPI contracts.

## Patterns

| Concern | Pattern |
|---|---|
| Service internals | Hexagonal architecture |
| Provider integration | Anti-corruption adapter around Keycloak Admin API |
| Browser authentication | Backend for Frontend (BFF) |
| Authorization | Tenant-scoped RBAC and method security |
| Rate limiting | Redis token bucket keyed by tenant |
| Secret handling | One-time secret with persisted hash only |
| Platform access | Time-limited, audited break-glass request |

## Runtime acceptance

Start the stack manually, then run:

```bash
cd /Users/rabiulbiswas/Desktop/openhire-web/backend
./scripts/testing/verify-phase1-runtime.sh
```

The script checks gateway health, both realm discovery documents, the public BFF session contract and the staff OIDC redirect. Browser review then covers verification, TOTP, password reset and logout UX.

Previously verified locally:

- Live tenant creation and role seeding in MongoDB.
- Invitation creation without persisted plaintext tokens.
- Verification response with recruiter redirect target.
- Redis rate-limit headers through the gateway.
- Valid API-key access returned `200`; invalid access returned `401`.

No Phase 1 code dependency blocks Phase 2.
