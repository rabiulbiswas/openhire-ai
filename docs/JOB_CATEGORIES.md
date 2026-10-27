# Tenant job categories and work modes

Organisation Admin manages **Job categories** at `/admin/job-categories`. Catalogs are isolated by tenant. Names are trimmed, whitespace-normalized, case-insensitively unique, and limited to 80 characters. Admin can add, rename, activate/deactivate, and delete. A used category is deactivated instead of deleted. Unused deletion retains a hidden tombstone for concurrent references and permits recreating the name.

Staff read active categories using `GET /api/v1/job-categories?page=0&size=100`. Admin reads all non-deleted entries using `GET /api/v1/job-categories/admin?page=0&size=20`. Both endpoints paginate on the server. POST creates; PUT `/{id}` updates with `revision`; DELETE `/{id}?revision=N` removes/deactivates with optimistic concurrency. Only Org Admin can mutate the catalog. Tenant and actor are derived from the existing trusted gateway/security context.

Every **new job**, including a new draft, must provide `categoryId` and `workMode` (`REMOTE`, `ONSITE`, or `HYBRID`). The category must belong to the tenant and be active when selected. These requirements also apply to API callers. Existing jobs are not backfilled or assigned invented values: older edits may omit both fields and keep their current values. Existing inactive selections remain valid for those jobs, but cannot be newly selected for another job.

Category identity, display name and work mode are captured in the immutable job-version classification snapshot. Editing unrelated content/interview plans preserves them. Category renaming does not rewrite approved job/approval history. Job details, public career summaries/details, new approval terms and version-change diffs expose the saved classification. External-provider adapters can read the same additive fields from the job snapshot.

No approval permissions or publishing gates were changed. Existing jobs can still follow their current approval/publication lifecycle. Fresh seed-job creation resolves an active category (or creates the default through an authenticated Org Admin); completed refreshes do not modify historical jobs.

Tests cover missing/invalid values, tenant separation, uniqueness, inactive selection, used/unused deletion, stale revisions, method-level admin authorization, snapshot preservation, form validation, and admin CRUD requests. Existing workflow suites remain regression coverage.
