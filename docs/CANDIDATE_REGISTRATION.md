# Verified candidate accounts

Public job browsing remains anonymous. Applying now requires a signed-in, email-verified **CANDIDATE-only** account. Staff registration, invitations, roles and existing staff login are unchanged. Candidate registration never reuses the staff provisioning operation, never changes existing staff roles, and never resets an existing account's password.

## Screens

- `/careers/register?returnTo=/careers/jobs/{id}/apply`: full name, email, current location, phone, password and confirmation. Step two accepts the emailed six-digit verification code. Registration completes only after verification; the candidate then signs in through the existing identity-provider login.
- `/careers/profile`: authenticated own-profile editing. Name, location and phone can be updated; verified email/identity/tenant/role cannot be changed here.
- The top account dropdown provides the existing `/candidate/portal` dashboard, profile update, and provider-aware sign-out. The dashboard also links to the new profile editor.
- Apply is disabled as **Applied** only for a CAREER application by that candidate to that job (including withdrawn applications). Browsing or signing in alone does not disable other jobs.

## Account and security boundary

`POST /api/v1/public/candidate-registrations` accepts name/email/location/phone only. Password remains in form memory until verification; it is not saved in browser storage or the registration database. The gateway derives the tenant from its registered active host and overwrites the public tenant header. No role or organisation supplied by the client can grant access.

`POST .../verify` accepts the opaque registration ID, code, password and confirmation. Codes expire after ten minutes, allow at most five attempts, have a 60-second resend cooldown and at most five sends per email/tenant/hour. Resends invalidate old challenges. Only keyed HMAC digests are stored. Atomic Mongo claims prevent parallel verification from provisioning multiple accounts. Temporary challenge records expire automatically. Existing-account requests return the same public response; those users should sign in or use existing account recovery.

On successful verification, the identity provider creates the verified candidate with a permanent password (hashed by the provider), assigns only CANDIDATE, then enables the account. Failed/partial provisioning is recoverable only for the same registration marker; unrelated existing identities are never overwritten. Active tenant membership and an own-profile record are persisted. As in the existing shared realm, an email already attached to another organisation requires explicit membership review rather than automatic reassignment.

`GET/PATCH /api/v1/candidate/profile` requires a verified candidate identity and active matching membership. The application service uses a secret-protected internal profile read to derive the applicant identity and contact details. Changing a profile does not rewrite historical submitted snapshots.

`POST /api/v1/candidate/applications` uses the existing resume/consent/question validation, processing and receipt flow, with authenticated JWT identity and BFF CSRF protection. A deterministic tenant/candidate/job storage key prevents duplicates across browser tabs or client keys. A legacy CAREER application for the same verified email/job also blocks reapplication. `GET .../status?jobId=...` is candidate/email/tenant/CAREER-scoped. Neither endpoint exposes staff, agency, referral or internal application records. Existing receipt URLs, consent controls and the dashboard's CAREER-only receipt lookup remain intact. The old anonymous `POST /public/applications` now returns 401 and cannot create records; old tracking/withdrawal capabilities still work.

`GET /bff/candidate-login?returnTo=...` stores only a strictly allowed local candidate page in the server session. The OIDC callback consumes it only for candidate login. External/protocol-relative paths, arbitrary staff paths and query-string redirects are rejected. No tokens or passwords appear in redirect URLs.

## Email configuration

Local Compose uses the existing Mailpit mailbox at `http://localhost:8025`; these emails do **not** reach Gmail or other real inboxes. For real delivery configure identity-service `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS`, and `MAIL_FROM` with a trusted SMTP relay. Configure HTTPS and a strong unique `CANDIDATE_VERIFICATION_SECRET` (at least 32 random bytes) outside local development. The local fallback is the existing internal service secret. Codes and passwords must never be logged. Verification mail failures are visible and fail closed.

Checks cover real isolated-Mongo expiry/cooldown/attempt/replay/concurrency behavior, candidate-only role boundaries, account/profile isolation, public browsing, authenticated application submission, Applied buttons, registration form validation, CSRF, and redirect allowlisting. Opt-in Mongo tests use `OPENHIRE_TEST_MONGO` and create/drop only randomly named `oh_candidate_registration_test_*` databases.

References: [Keycloak Admin REST API](https://www.keycloak.org/docs-api/26.7.4/rest-api/index.html), [OWASP account recovery token guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
