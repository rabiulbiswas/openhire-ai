# Tenant naming and dynamic ID migration

Status (2026-10-23): additive platform tenant APIs, canonical-first claim readers and the platform UI consumer
changes are implemented in source. A synthetic-data rehearsal on a separate temporary MongoDB passed.
The live database/schema, Keycloak attributes/mappers and running services have **not** been changed.
This remains an expansion stage, not a completed schema cutover or a full application rename.

## Collection strategy

Use service-owned databases with shared collections per entity, **not collections per tenant**. For example,
identity-service owns `tenants`, `users`, `roles` and `invites`; every tenant-owned user/role/invite has a
`tenantId`. The tenant registry uses its stable `_id` as the tenant ID. A UUID is generated once on onboarding;
the host slug resolves to that existing ID on later requests. Do not place one tenant ID in deployment config.

Tenant-owned reads, updates and deletes must include the verified tenant ID, not trust a client-supplied ID.
Use compound indexes such as `{ tenantId: 1, email: 1 }` (unique per tenant) and
`{ tenantId: 1, name: 1 }` for roles. Global platform records are a separate concern; do not create a business
tenant for a Super Admin session. Database-per-tenant can be a later explicit isolation requirement, not an
automatic collection naming rule. Current legacy identity collections remain in place until cutover.

## Expansion delivered

- `/api/v1/platform/tenants`: GET list, POST create.
- `/api/v1/platform/tenants/onboard`: POST onboarding; response `{ tenant, adminInvitation }`.
- `/api/v1/platform/tenants/{id}`: GET tenant overview; response uses `tenant`.
- `/{id}/admin-invitations`: POST; `/{id}/status`: PATCH.
- `/{id}/quota`: GET/PUT; `/{id}/break-glass`: GET/POST with canonical `tenantId` response.
- All these routes require `SUPER_ADMIN`. Existing `/platform/organizations` routes remain unchanged for
  compatibility. Both route families use the same application service and stable database IDs. New invitation
  DTOs omit stored hashes and use no-store responses for invitation tokens.
- Gateway, identity authorization, shared service authorization, application intake and SAML/SCIM controllers
  accept signed `tenant_id` first, allow matching legacy `organization_id` during transition, and reject
  conflicting, blank, whitespace-padded or non-string tenant claims.
- OIDC session construction no longer defaults to the configured demo tenant. Super Admin uses a platform
  session context; tenant users without a tenant claim are denied. Header fallback is restricted to explicitly
  selected local security mode; authenticated JWT callers cannot use local fallback to replace a missing claim.
- All 18 service application configurations now have empty optional local tenant defaults, including job-service;
  Compose and Java injection fallbacks no longer reintroduce `demo-org`/`demo-tenant`. Sample identity seeding is
  disabled unless explicitly enabled with a sample tenant ID. Existing fixture records/imports are not deleted.
  Local BFF fixture sessions require an explicit tenant; public anonymous consent requires an explicit tenant ID,
  while authenticated consent resolves it from the token. Metrics no longer select demo tenants by default.
- Platform UI uses the new tenant APIs and `/tenants`; `/organizations` redirects for existing bookmarks.
  Existing legacy frontend exports remain for consumers not yet migrated.

Still required before live migration: canonical Java persistence models/repository queries/index configuration,
remaining tenant-owned response DTOs and API-key consumer contracts, Keycloak profile/mapper and provisioning
changes, remaining UI/scripts/generated contract updates, and full application runtime acceptance. In particular,
do **not** apply the Mongo script to the live database while the current legacy repository is deployed: it still
reads `organizations`/`organizationId`, and the canonical `Tenant` persistence model is not yet installed.

## Agreed contract

- Use Tenant throughout the product and first-party API/database/claim names.
- Generate a UUID once when Super Admin onboards a tenant. Preserve it permanently; never generate it per login
  or replace an existing ID during this migration. Resolve the active tenant dynamically on each request.
- Resolve `<slug>.localhost` against the tenant registry in identity-service. The slug is an address, not the ID.
- `tenant_id` is the canonical Keycloak user attribute and signed claim; `tenant_name` is descriptive metadata.
- `tenantId` is the canonical Java/JSON/Mongo reference field. The tenant registry document's `_id` remains its
  stable tenant ID; `name` and `slug` retain their current meanings.
- API paths become `/api/v1/platform/tenants`; platform navigation becomes `/tenants`.
- Tenant onboarding returns `{ tenant, adminInvitation }`; overview returns `{ tenant, ...counts }`.
- No authenticated request may silently fall back to `demo-org` or another configured workspace. Demo data and
  any local-only fixture identity must be explicitly enabled. No per-tenant environment variable is required.
- Existing ORG_ADMIN role identifiers remain stable during this schema migration; display labels become Tenant
  administrator. Changing role identifiers would be a separate permission migration.
- Microsoft 365's `organizations` authority and Keycloak's native organization-related template variables are
  upstream protocol identifiers, not OpenHire tenant fields, and must remain unchanged.

## Names to migrate

| Existing name | Canonical name |
| --- | --- |
| `organizationId` / `organization_id` | `tenantId` / `tenant_id` |
| `organizationName` / `organization_name` | `tenantName` / `tenant_name` |
| `IdentityModels.Organization` / `OrganizationStatus` | `IdentityModels.Tenant` / `TenantStatus` |
| `IdentityRepositories.Organizations` | `IdentityRepositories.Tenants` |
| MongoDB `organizations` collection | `tenants`, preserving document IDs |
| `/platform/organizations` | `/platform/tenants` |
| UI `OrganizationsPage` and organization hooks | `TenantsPage` and tenant hooks |

Identity-service repositories, indexes, SCIM/SAML ownership checks, invite responses and sample-data settings
must move together. The gateway, shared service authorization, API-key introspection consumers, frontend API
types, mocks, setup/verification scripts and OpenAPI references must also agree on the same canonical claim.
Previously issued tokens and stored browser sessions retain the old claim until users sign in again.

## Staged implementation

1. **Expand the contract.** Add canonical tenant DTOs/paths with transitional legacy aliases. Accept the canonical
   claim first; while compatibility is required, reject tokens containing conflicting old/new tenant claims.
   Preserve existing data reads during the transition and document deprecation. Add focused contract checks
   before changing consumers. Preserve the Super Admin platform context without inventing a business tenant.
2. **Update consumers.** Switch frontend routes, API-client payloads, labels and caches. Switch downstream
   service authorization and API-key consumers. Remove OIDC default-tenant fallback; retain explicitly selected
   local fixture behavior separately. Test platform and tenant login, cross-tenant rejection and suspended users.
3. **Rehearse data migration.** Use a copy of the identity database. Dry-run the script below; verify conflicting
   legacy/canonical fields are rejected and IDs, roles, invitation hashes and API-key hashes are unchanged.
4. **Maintenance cutover.** Take an independent recoverable database backup and export the affected Keycloak
   client/profile settings. Stop identity writers, API-key/user provisioning, SCIM writes and affected workers.
   Apply the Mongo migration only after rehearsal. Migrate existing Keycloak user attributes and client-scope
   mappers through the operator account; retain administrator-only edit access to `tenant_id`. Do not grant
   identity-service client-management permissions, change callbacks or alter realm role grants.
5. **Resume and verify.** Start compatible services, then log out and sign in again to obtain new signed claims.
   Verify existing tenant IDs and counts, create a new tenant, invite its admin, sign in through its own host,
   test each role and API-key tenant, and verify two tenants cannot access each other's data. Passwords and MFA
   credentials must remain unchanged. Keep migration backups until acceptance.
6. **Contract the old schema.** After acceptance, remove legacy API aliases, old claim readers and compatibility
   fields. Update remaining documentation/generated specs. Retain historical audit/event records as history;
   never rewrite their tenant IDs. Publish changed event names/versioning separately if required by consumers.

## MongoDB migration tooling

Prerequisite: `mongosh`, connected to the correct identity database. Run from the backend repository root.
The helper retains BSON values and refuses conflicting tenant values. The tooling is separate from application
repository changes and must not be used as a standalone live upgrade.

```bash
# Read-only preflight: reports counts, legacy fields and affected indexes.
mongosh 'mongodb://127.0.0.1:27017/openhire_identity' \
  --file scripts/maintenance/migrate-tenant-fields.mongosh.js

# Apply ONLY during the maintenance step, after an independent backup and rehearsal.
OPENHIRE_TENANT_MIGRATION_APPLY=true \
OPENHIRE_TENANT_MIGRATION_MAINTENANCE=true \
mongosh 'mongodb://127.0.0.1:27017/openhire_identity' \
  --file scripts/maintenance/migrate-tenant-fields.mongosh.js
```

For another database name, explicitly set `OPENHIRE_TENANT_MIGRATION_DB`; the script rejects a connection to a
different database. It examines only known identity collections. If both registry collections already exist,
it stops for operator review rather than merging/dropping data.

Apply snapshots the original records into `__tenant_rename_backup_v1_<collection>` and original index specs
into `__tenant_rename_manifest_v1`, preserving the first snapshot on retries. It populates canonical fields
before building replacement indexes, then removes old indexes/fields, and finally renames the registry
collection. This ordering avoids collisions in old unique compound indexes when multiple tenants share the
same email or role name. A failed new index build leaves the corresponding legacy index/fields present.

Writers must remain stopped throughout. This multi-collection operation is not a transaction. On failure,
inspect the index manifest and backup collections before resuming; do not automatically roll back a database
that has accepted new application writes. Use the independent backup for a full maintenance rollback, and
restore matching application/Keycloak versions and claims together.

## Rehearsal and validation

The 2026-10-23 rehearsal used a separate temporary `mongod` on loopback port 27939 and synthetic-only database
`openhire_tenant_rehearsal_2a3566d3afc945acaa951dcdfe30f56f`. It did not copy or connect to the live database.
It verified dry-run makes no writes; apply and repeated apply succeed; stable IDs, BSON dates and synthetic
credential hashes survive; registry rename and tenant-scoped unique indexes work; same-tenant duplicates are
rejected while cross-tenant identical emails/roles survive; unknown collections stay untouched; backups exist;
conflicting fields, wrong-database targets and missing maintenance approval are rejected.

The rehearsal found and repaired a cross-JavaScript-realm index transformation bug in the Mongo shell. A
regression test now covers that case. For repeatable testing, start a separate empty MongoDB on port 27939
using a temporary data directory, then from the backend root run:

```bash
MONGOSH_BIN=/path/to/mongosh node scripts/testing/rehearse-tenant-migration.mjs
```

This command uses a generated synthetic database name on **port 27939 only**. It retains fixture databases
inside the temporary instance for inspection; stop that instance after testing. It is not a production backup
restore rehearsal and does not establish live application compatibility.

Run `node --test scripts/testing/tenant-field-plan.test.mjs` for transformation, conflict, idempotency, BSON-value
preservation and index-planning checks (5 tests passed). Platform UI type-check passed. Both Java claim-reader
utilities were checked in isolation against cached Spring dependencies for canonical/legacy/matching/conflicting,
invalid and absent claims. No Java application build, service restart or live login workflow was run. No live
migration, user claim change or realm/client configuration change was performed. Legacy persistence remains
active until the coordinated repository/Keycloak cutover and runtime acceptance stages.
