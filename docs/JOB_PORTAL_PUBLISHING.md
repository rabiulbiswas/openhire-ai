# Job publishing and application attribution

## Implemented behavior

Final approval publishes the current approved job on OpenHire automatically. The existing manual publish endpoint remains idempotent for compatibility and for older approved-but-unpublished jobs. No migration publishes historical jobs. Approval callbacks cannot reopen paused/closed jobs. Drafts and unapproved versions cannot publish externally. Currently published jobs remain immutable under the existing job-edit workflow.

Job publication saves an embedded recovery marker atomically with the job state. The recovery worker inserts deterministic approval/publication outbox events, then clears the marker. Duplicate inserts preserve the existing event's delivery state. This closes the save/outbox failure window without requiring Mongo replica-set transactions.

Organisation Admin settings are under **Connectors → External job portals**. Credentials are tenant-scoped `secret://<tenant-id>/...` references to read-only server-mounted secrets; the API never returns secret values/references. Recruiters, HR Admins and Organisation Admins control per-job switches under **Where it's posted**. Hiring Managers may view but cannot publish externally. A connection must be active, provider-verified and dispatch-enabled before a new ON request is accepted.

Each tenant/job/provider has one durable desired-state record. Updates use optimistic revisions; stale requests return 409. States are QUEUED, PUBLISHING, PUBLISHED, REMOVING, REMOVED and FAILED. An OFF request always requests removal, not just a local hidden flag. Failed operations retry after a delay and support explicit retry. Remote IDs are retained even when an OFF request arrives during CREATE. Confirmed status updates are revision-fenced so an older response cannot overwrite a newer OFF request. Job-close/pause events queue removal; periodic authoritative job reads also repair missed lifecycle events. Reopening does not automatically re-enable an external switch.

Connection rotation/disable and enqueueing share a provider-scoped database lease. A connection cannot change while any posting remains unremoved. Credentials must remain available until removals are confirmed. Do not disable dispatch or remove mounted credentials while remote postings remain live.

## Provider activation is deliberately blocked

There are **no production LinkedIn or Naukri adapters in this delivery**. Both display `PROVIDER_ACCESS_REQUIRED`; saving a secret reference does not verify them or expose recruiter switches. No synthetic provider success is used. No external job or application has been sent.

LinkedIn requires approved Talent Solutions partner access, customer authorization and contract-compatible publishing. Some Apply Connect arrangements require all public jobs rather than selective per-job publishing. Do not activate a selective adapter unless the granted contract permits it. Naukri must supply its approved OpenHire integration contract, endpoints, credentials and application-delivery authentication. An integration specific to another ATS is not a reusable Naukri contract.

Once approved documentation and test credentials are available, implement `JobPortalProvider` for that provider. Verification must check authorization plus selective publish/update/remove capabilities. Reconcile must use a stable tenant/job/provider key, recover a timed-out create by key, poll asynchronous provider tasks, and return `confirmed=true` only after observing the actual remote published/removed state. Enforce network timeouts well below the 120-second lease, bounded responses, fixed trusted vendor hosts, scrubbed errors, token refresh and vendor-specific rate limits. A distributed lease does not provide remote fencing after process suspension; adapters must reconcile by stable identity and reject/repair stale operations according to the vendor contract. Add provider contract tests before activation.

Set `INTEGRATION_JOB_PORTALS_ENABLED=true` only after provider contract testing and connection verification. Default is false. Existing OpenHire career publishing does not depend on this external-dispatch flag.

## Application intake and reporting

The application service keeps existing intake types (CAREER, AGENCY, RECRUITER, INTERNAL, REFERRAL, PUBLIC_API). Verified external imports add a separate `sourceProvider` field (LINKEDIN/NAUKRI). This preserves candidate-login and consent semantics. Ordinary/public callers cannot set provider attribution by supplying that field.

`POST /api/v1/internal/portal-applications/{provider}` is service-secret authenticated, is not routed publicly, and accepts normalized application fields. A provider adapter must first verify its vendor callback/signature, derive the tenant/job from the stored remote posting, and supply an external application reference. The existing current-consent, processing-consent and job-open validations apply. Consent is never fabricated. External references deduplicate per tenant/provider/job across delivery retries. A conflicting payload for the same identity is rejected.

`GET /api/v1/jobs/{id}/application-sources` reports actual tenant/job-scoped submission counts. `GET /api/v1/jobs/{id}/applications?channel=LINKEDIN&page=0&size=10` filters and paginates on the server. Career, agency and other source filters remain supported. The job's posting tab exposes these source counts and paginated results. Generic PUBLIC_API filtering excludes provider-attributed imports, matching the displayed count.

## Verification and operations

Job service tests cover automatic publication, approval-version checks, duplicate callbacks and closed-job protection. Integration tests use an isolated test database and a test-only mock provider: unverified activation rejection, revision conflicts, secret namespace validation, connection-removal guard, async confirmations, OFF-during-CREATE, remote ID retention and closed-job removal. Controller tests exercise role and tenant boundaries. Application tests cover provider attribution, deduplication and consent. Frontend tests cover unavailable-provider switches and server-side source filtering.

Run database-backed integration tests with `OPENHIRE_RUN_MONGO_TESTS=true`; these create and remove only uniquely named test databases. Live provider acceptance testing remains blocked on vendor access. Test outgoing mail continues to use the existing local Mailpit setup.
