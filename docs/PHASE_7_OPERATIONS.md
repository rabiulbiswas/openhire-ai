# Phase 7 — low-memory monitoring and record retention

These additions do not require increasing Docker Desktop's memory allocation. They do not fix the separate
ClamAV/full-stack memory problem, and a container cap is not a guarantee that a saturated host has spare memory.

## Deployment

The changed deployable services are `compliance-service`, `ai-gateway` and `analytics-service`.
Rebuild them one at a time and recreate only those services after their existing dependencies are available.
The implementation was tested independently; do not assume already-running containers contain these changes.

From `backend`, with the existing stack running:

```sh
export COMPOSE_PROFILES=phase3,phase4,phase5,phase6,phase7
docker compose build compliance-service
docker compose build ai-gateway
docker compose build analytics-service
docker compose up -d --no-deps compliance-service ai-gateway analytics-service
./run-backend.sh monitoring
```

No `down`, volume deletion or full-stack rebuild is needed. Monitoring is deliberately not part of the automatic
Phase 7 startup profile: start it only when sufficient headroom is available, or stop unneeded development services
for independent monitoring tests. Do not disable malware checks to compensate for insufficient resources.

## Prometheus

- Pinned local image: `prom/prometheus:v3.5.0`, 192 MB container cap, 128 MiB Go memory target, 0.5 CPU.
- One scrape target, every 60 seconds, at most 1,100 samples, 512 KB response cap, eight labels per sample.
- 24-hour/128 MB time-series retention; two concurrent queries, maximum 100,000 query samples.
- UI binds only to `127.0.0.1:9090`. No Grafana or Alertmanager process is required. Alerts are evaluated and
  visible in Prometheus; external alert delivery is not configured.
- `PROMETHEUS_TENANTS` lists tenants to export (default `demo-org,second-org`, maximum 100). No candidate,
  application, email or request identifiers are exported. Global aggregates have a separate `scope` label.
- Scrapes require `X-Internal-Service-Secret`. The mounted `internal-secret.local` contains only the already-used
  local development default. If `INTERNAL_SERVICE_SECRET` is changed, point `PROMETHEUS_SECRET_FILE` to a protected
  local file containing the matching value. Never use the committed development credential in production.
- The SLO API prefers fresh Prometheus samples and labels any fallback. Sampling/export time is not a claim of
  histogram precision: availability is health-poll based, latency remains approximate, and workflow timings derive
  from event timestamps. Empty measurements are not exported as successful zero-valued samples.
- Error-budget exhaustion and exporter-unavailable rules are in `infra/prometheus/alerts.yml`.

Validate configuration without starting other services:

```sh
docker compose --profile monitoring run --rm --no-deps --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
```

## Audit and AI-log retention

`POST /api/v1/retention-policies/run` defaults to a non-mutating dry run. The existing daily UTC scheduler also
executes the record policies. Policy configuration remains tenant-scoped and disabled policies are skipped.
`recordPolicies` in the response includes `key`, `status`, `processed`, and, for evaluated batches,
`eligibleInBatch`, `limit`, `dryRun`. Failure is `FAILED`; a held AI policy is `BLOCKED_BY_LEGAL_HOLD`.
The console preview displays these outcomes. Last-run metadata and RETENTION_RUN audit details record results.

### Audit records

After the configured age (default 84 months), create at most 50 verified archival copies per tenant/run.
`audit_archive` stores the original record, SHA-256 digest and archival time. Inserts are idempotent and verified
before reporting success. The original append-only ledger is retained, so verification, export, query and duplicate
event detection remain intact. This is logical archival in MongoDB, **not** a cold-storage migration, disk-space
reclamation, WORM storage or an independent disaster-recovery backup.

### AI request logs

After the configured age (default 12 months), compliance calls the AI gateway over the existing internal-secret
boundary. The gateway deletes only its own `ai_requests` records, within the tenant and before the cutoff, at most
the batch limit. It rechecks the cutoff and unpublished `_events` at deletion time. Pending outbox events and newer
logs are preserved. A live cutoff newer than one month is refused. Dry runs only count the next bounded batch.

AI request rows do not carry reliable candidate/job mappings. Consequently **any active legal hold for the tenant
blocks AI-log erasure**. This favors preservation over unsafe deletion. Audit archival remains non-destructive.
An unavailable AI owner reports failure rather than success; the next sweep can retry. Only discovered audit,
application or AI-log tenants are evaluated; AI-only tenant discovery retries next sweep after an outage.

Default batch is 50 (`openhire.compliance.retention-batch`); the owner endpoint rejects limits outside 1–500.
Manual production erasure should always follow a reviewed dry run. Deleted logs are not recoverable without a backup.

## Verification

```sh
OPENHIRE_RUN_MONGO_TESTS=true ./gradlew :compliance-service:test :ai-gateway:test :analytics-service:test --max-workers=1 --no-daemon
node scripts/testing/verify-phase7-contracts.mjs
```

Database tests create and drop only uniquely named `oh_phase7_*` fixture databases. Tests cover unchanged audit
hash chains after archival, idempotent batches, tenant isolation, cutoff protection, pending-event preservation,
legal holds, internal authentication, Prometheus freshness/escaping/fallback, and console retention previews.
The full candidate/AI/privacy/browser acceptance gate remains separate and still requires a healthy scanner.
