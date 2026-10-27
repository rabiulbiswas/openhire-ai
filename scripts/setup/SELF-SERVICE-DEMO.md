# Candidate and employee portal demo refresh

Run against an existing local tenant demo using its original demo password in
`OPENHIRE_SEED_PASSWORD` (never commit that password):

```sh
node scripts/setup/seed-tenant-demo.mjs --tenant=codelogicx --data-only --resume --self-service-refresh --apply
```

Requires current application-service, the original OPEN demo Backend Engineer and
Data Engineer jobs, existing demo employee account/onboarding, and Mailpit SMTP.
It adds two candidate applications with secure receipts, two actual message
threads, two employee referrals and one employee internal application. No
candidate login account or new realm role is created. No existing passwords,
checklist progress, jobs or hiring decisions are reset.

Progress and receipt credentials are checkpointed under `selfServiceRefresh` in
the existing tenant's demo manifest. Treat its receipt tokens as credentials;
never commit, publish or log them. Candidate links use
`/candidate/portal#receipt=…&application=…&tenant=…`; after verification the browser
keeps the receipt in that tab's session storage and removes it from the address.
The fragment is not transmitted to the web server. All candidate APIs still
require a valid matching receipt and tenant.

Employee screens use normal tenant login: `/internal-jobs`, `/referrals/new`,
`/referrals`, `/onboarding`. Referral pagination is server-side and scoped to the
verified employee, tenant and INTERNAL/REFERRAL sources. AI scores and internal
notes are excluded. Seed scripts do not fabricate workflow stages or assessments.

Rerun the command after interruption. Stable idempotency keys and saved receipts
avoid duplicates, and already-created examples are left unchanged.

Candidate password login is optional and must be explicitly authorized by the
operator. `node scripts/setup/create-candidate-demo-logins.mjs --apply` creates
accounts only for the two existing `demo.portal-*` fixtures. It adds the CANDIDATE
realm role if absent, grants no staff roles, preserves existing passwords and
prints only newly created demo credentials. Accounts use the normal tenant
`/login` flow. After login the application service verifies the candidate role,
verified email and tenant before issuing a 24-hour capability for the matching
CAREER application. Original secure links remain valid; only five recent login
capabilities are retained. Internal notes and AI scores are never returned.

```sh
node --test scripts/setup/refresh-self-service-demo.test.mjs
```
