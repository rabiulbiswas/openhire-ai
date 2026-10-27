# Existing tenant agency demo refresh

Requires the original tenant demo manifest, existing demo staff accounts, two
unchanged OPEN demo jobs (Backend Engineer and Data Engineer), the current
application-service, and local Mailpit delivery. No accounts or passwords are
created or reset. Candidate records are created without candidate login accounts.

Supply the original demo password through `OPENHIRE_SEED_PASSWORD`, then run:

```sh
node scripts/setup/seed-tenant-demo.mjs --tenant=codelogicx --data-only --resume --agency-refresh --apply
```

This adds two job assignments and six synthetic agency submissions using normal
authenticated APIs. Phone, notice period, candidate reason, consent and a valid
synthetic PDF are included. Actual workers determine processing status; scores,
audit events and hiring decisions are not fabricated. Public agency pagination
and per-job submission counts are checked after insertion.

Progress is checkpointed under `agencyRefresh` in the existing tenant demo
manifest. Rerun the same command after interruption; intake uses stable
idempotency keys and external references. Completed fixtures are not reset, and
later manual assignment/workflow changes are not overwritten. Missing or changed
original demo jobs stop the refresh instead of modifying business records.

Run helper tests with:

```sh
node --test scripts/setup/refresh-agency-demo.test.mjs
```
