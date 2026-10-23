# Backend package architecture

Source checkpoint: 2026-10-23. The package standardization covers all 18 runnable backend services.
This is a source refactor, not a database migration, new deployment, or certification of runtime workflows.

## Standard layout

```text
com.openhire.<service>
├── <Service>Application.java
├── controller
├── dto
│   ├── request
│   └── response
├── domain
├── service
│   ├── port
│   └── impl                 # only when an implementation abstraction is useful
├── repository
│   ├── mongo
│   └── redis
├── client                   # HTTP, identity providers, AI providers, delivery channels
├── messaging
│   ├── consumer
│   └── publisher
├── scheduler
├── config
├── security
├── exception
├── mapper                   # when mapping logic warrants a separate class
└── utility                  # only genuinely generic helpers
```

Create packages when they contain real components; empty folders are not part of the standard. AI workers keep
their existing `integrity`, `matching`, `shared` and `privacy` feature boundaries, with layers inside them.
The reactive gateway keeps reactive security filters, routing and Redis session configuration; it does not
receive a servlet repository/service hierarchy just to resemble CRUD services. Shared libraries retain their
cohesive audit, event, tenant, security and privacy APIs rather than pretending to be independent REST services.

## Responsibilities and dependencies

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP binding, validation, authorization boundary, response/status selection; delegate workflows to services |
| `dto` | Transport request/response shapes; mixed existing record containers can remain directly under `dto` |
| `domain` | Business models, invariants, states and domain-specific calculations |
| `service` | Use-case orchestration; concrete Spring services are sufficient when no interface abstraction is needed |
| `service.port` | Existing store/client/event contracts that keep business workflows independent of implementations |
| `repository` | Tenant-scoped persistence and cache implementations |
| `client` | Outbound HTTP, Keycloak, model providers and delivery-channel implementations |
| `messaging` / `scheduler` | Kafka entry points, outbox publishing and scheduled entry points |
| `config` / `security` | Bean wiring, configuration, authentication and tenant authorization |
| `exception` | API exception translation and related exception types |
| `mapper` / `utility` | Focused conversions and small generic helpers; never a dumping ground for business logic |

The intended dependency direction is controller -> service -> repository/port/client. Services must not call
controllers, schedulers or Kafka consumer entry points. Domain code must not depend on outer application layers.
The existing framework-free domain and service restrictions in advanced services remain checked by ArchUnit.
Do not manufacture `Service` + `ServiceImpl` pairs for a single implementation, or make controllers depend on
implementation names merely because there is an `impl` directory in this example.

## Applied changes

- Relocated 363 existing main Java types to the standard layer packages, with corresponding imports, test
  packages and source references updated. Application entry points and component-scan roots are unchanged.
- Organized existing API DTO files into `dto`, `dto.request` or `dto.response` where appropriate. Existing nested
  endpoint records are not all extracted; their JSON fields and signatures remain compatible.
- Extracted hiring-stage and pipeline-template orchestration into `HiringConfigurationService`. Its controller
  now delegates to the service, and `StageSetRequest` / `TemplateRequest` live in `dto.request`.
- Split `/openapi/config` HTTP handling into `OpenApiPortalController`; route construction remains in config.
- Moved mixed workflow Kafka consumers/publishers out of controller/service packages. The matching command's
  stable topic and embedded-field names are shared through a small messaging contract, not its publisher bean.
- Updated existing architecture rules for layered dependencies and added architecture checks to the five
  services that did not already have them. The package checker also validates Java package/file alignment,
  original HTTP mapping annotations, moved type presence and preserved domain/persistence class identities.
- Regenerated all 18 REST reference documents from the relocated sources.

## Persistence compatibility: deliberate exceptions

All 108 existing domain/persistence container class identities in the baseline remain at their original paths.
In particular, these four types deliberately retain their older package names because MongoDB may store their
fully qualified class names in `_class` (including nested model names):

- `com.openhire.applicationservice.adapter.out.mongo.ApplicationDocument`
- `com.openhire.jobservice.adapter.out.mongo.OutboxEventDocument`
- `com.openhire.approval.adapter.out.events.ApprovalOutboxEvent`
- `com.openhire.jobservice.configuration.HiringConfiguration`

Their adapters/repositories moved, not the persisted types. Some conversion/constructor visibility was made
public to allow the new package boundary; collection names and document fields were not changed. Moving these
models later requires an explicit alias/mapping compatibility strategy and data rehearsal. The separate
[tenant naming migration](TENANT_NAMING_MIGRATION.md) remains pending; this package refactor does not execute it.

The inventory and compatibility baseline is [package-refactor-baseline.json](architecture/package-refactor-baseline.json).
Its old/new class mapping is historical input to the checker; do not mechanically rename its `from` values.

## Remaining dependency cleanup (not hidden by package moves)

The package layout is standardized; full separation of every legacy workflow is **not** complete. The following
existing controllers still directly use persistence implementations or `MongoTemplate` and need dedicated
service/repository extraction in a follow-up, with their security/workflow behavior verified:

| Service | Existing controller boundary |
| --- | --- |
| identity-service | `SamlController` |
| application-service | `PrivacyLookupController` |
| approval-service | `DelegationPeriodController` |
| integration-hub | `CalendarWorkController`, `CalendarSyncController`, `WebhookDeliveryController`, `McpController` |

Some older service classes also directly use `MongoTemplate` (for example candidate management, resume processing,
SCIM provisioning and submission workflows). They were not rewritten into new persistence abstractions merely
to rename their packages. These are dependency-cleanup tasks, not a claim that business functionality is missing.
Likewise, nested transport DTO extraction and generic mapper/utility extraction should be done where useful,
not by changing persisted domain models or adding empty wrappers. Tenant isolation must remain enforced during
any later extraction; a package name does not provide authorization.

## Checks and local commands

Validation completed for this refactor: all production/test source sets compiled; 51 architecture checks passed
across all 18 services; 34 Node tooling/configuration tests passed; the package/route compatibility check and
all 18 generated REST reference freshness checks passed. No live workflow or full acceptance suite was run.

```bash
# Structural checks: no JVM, MongoDB, Redis, Kafka or Docker is required.
node scripts/maintenance/package-layout.mjs
node --test scripts/testing/package-layout.test.mjs
node scripts/maintenance/service-reference.mjs --check

# Compile production and existing test source sets, without packaging or deploying applications.
./gradlew compileTestJava --offline --no-daemon --max-workers=2

# Example: run only one service's architecture checks (no live dependencies).
./gradlew :identity-service:test --tests '*ArchitectureTest' --offline --no-daemon

# Formatting and generated endpoint documentation.
./gradlew spotlessCheck --offline --no-daemon
node scripts/maintenance/service-reference.mjs --write
```

Offline commands require the dependencies already in the Gradle cache. New machines need an initial dependency
download. Full unit/acceptance suites and deployed login/API workflows remain separate from package checks.
No services were restarted and no application data or Keycloak settings were changed by this refactor.
