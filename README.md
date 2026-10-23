# OpenHire backend

Standalone backend repository for OpenHire: **18 runnable services and two shared libraries**. The frontend is
maintained separately in `openhire-frontend`. Run commands here from the directory containing `settings.gradle`;
there is no enclosing `backend/` subdirectory.

Implementation spans Phases 0–8, including recruiting, AI, compliance and enterprise integrations. This does not
mean every phase is production-accepted. Phase documents contain historical evidence; consult the
[remaining-work register](docs/PHASE_IMPLEMENTATION_GAPS.md) for known gaps and validate the current commit in CI
and your target environment. Live provider credentials, enterprise interoperability, security and disaster-recovery
acceptance remain separate from offline builds/tests. Older phase documents may still use pre-split paths.

Quick links: [services](#active-modules) · [build and test](#local-build-and-test--step-by-step) ·
[CI](#github-cicd) · [service designs and APIs](docs/services/README.md).

## Technology baseline

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.2, Gradle 9.2.1
- MongoDB business persistence: for example `openhire_identity`, `oh_job`, `oh_approval`, `oh_resume` and
  `oh_integration`; Compose defines each URI. AI gateway/workers share `oh_ai`.
- Redis-backed BFF sessions and gateway rate limits
- Kafka domain events, outbox delivery and asynchronous processing
- Keycloak 26.8 for staff/candidate OIDC; isolated local identity adapter remains available for development

## Active modules

Default ports below are from `docker-compose.yml`. Browser requests belong at the gateway; direct service ports
are for local diagnostics. Host ports and container ports differ for several later services.

| Service | Responsibility | Host → container port |
| --- | --- | --- |
| `api-gateway` | BFF sessions, OIDC, routing and rate limits | 8080 → 8080 |
| `identity-service` | Organizations, members, roles, SSO/SAML and SCIM | 8081 → 8081 |
| `job-service` | Requisitions, versions, publication and drafting | 8082 → 8082 |
| `approval-service` | Approval inbox, decisions and delegation | 8083 → 8083 |
| `application-service` | Intake, pipeline and human decisions | 8084 → 8084 |
| `candidate-service` | Candidate identity, profile and reviewed merges | 8085 → 8085 |
| `resume-processor` | Quarantine, scanning, extraction, OCR and retries | 8086 → 8086 |
| `interview-service` | Scheduling, scorecards and debrief | 8087 → 8087 |
| `communication-service` | Conversations, templates and preferences | 8088 → 8088 |
| `notification-dispatcher` | Channel delivery and retries | 8089 → 8089 |
| `offer-service` | Offer terms, approvals and responses | 8095 → 8090 |
| `employee-service` | Hire saga, onboarding and referral rewards | 8096 → 8091 |
| `ai-gateway` | Model calls, guardrails, prompts and usage | 8097 → 8092 |
| `ai-workers` | Matching and integrity processing | 8098 → 8093 |
| `talent-service` | Consent-aware search and rediscovery | 8099 → 8094 |
| `compliance-service` | Audit, privacy, retention and fairness | 8105 → 8095 |
| `analytics-service` | Metrics, SLOs and failed-event recovery | 8106 → 8096 |
| `integration-hub` | Calendar adapters, webhooks, MCP and budgets | 8107 → 8097 |

Shared libraries: `platform-starter` supplies tenant context, event envelopes and common errors;
`service-support` supplies workflow security, indexes, privacy helpers, internal clients and outbox facilities.
Neither runs as a service. Integrity processing lives in `ai-workers`, not a separate nineteenth service.

Repository layout: service folders contain code/tests; `gradle/` and root Groovy build files manage dependencies;
`contracts/` holds API/event contracts; `scripts/` contains local tooling and acceptance checks; `infra/` contains
Keycloak, Helm and monitoring configuration; `.github/` contains this backend's CI and disabled deployment scaffolding.

Scripts are grouped into `scripts/deployment/`, `scripts/testing/`, `scripts/setup/` and `scripts/maintenance/`.
See the [script guide](scripts/README.md) for purposes, commands and safety notes. `./run-backend.sh deploy` remains
the standard local deployment entry point.

See the [infrastructure guide](infra/README.md) for every Keycloak, Helm and Prometheus file, prerequisites and
the distinction between local development configuration and future Kubernetes deployment.

## Architecture

All 18 runnable services now use the standardized [backend package layout](docs/BACKEND_PACKAGE_ARCHITECTURE.md).
The guide explains layer responsibilities, deliberate MongoDB class-name compatibility exceptions, checks,
and remaining legacy dependency cleanup. Package moves do not migrate tenant data or deploy services.

Tenant-specific login and the independent Super Admin portal are being introduced incrementally.
The [tenant naming migration](docs/TENANT_NAMING_MIGRATION.md) records the additive `/platform/tenants` APIs,
canonical signed-claim handling, shared-collection design and completed isolated Mongo rehearsal. Live schema
cutover is **not** complete; do not run its migration against the application database with the legacy repository.
See [tenant portals and onboarding](docs/TENANT_PORTALS.md) for implemented APIs, local-domain design,
security requirements and remaining work. Business plans are deferred. Tenant login is opt-in and requires
operator-managed Keycloak clients, exact callback URLs and email/theme setup. Local addresses use
`admin.localhost` and `<tenant>.localhost`, with no hosts-file edits needed in modern browsers.
After that setup, use `./scripts/deployment/deploy-local.sh --tenant-portals identity-service api-gateway`.
This enables the proxy and tenant checks; it does not perform Keycloak setup or establish runtime acceptance.

The earlier domain modules use ports/adapters. Phase 3 also includes controller/use-case workflow components
that coordinate Mongo-backed processing records and internal service clients:

```text
domain <- application/use cases <- inbound adapters (HTTP/Kafka)
                              -> outbound ports -> Mongo/Redis/Keycloak/Kafka adapters
```

Keycloak-specific models and calls stay in the identity provider adapter. Phase 3 workflow components
use MongoTemplate directly for conditional claims and embedded-outbox updates; they are not persistence-free domain code.

## Local deployment — Docker with live logs

### 1. Prerequisites

- Docker Desktop/Engine running, with Docker Compose v2 supporting `up --wait` and `--wait-timeout`.
  Local `--no-scan` mode requires Compose 2.24.4+ for the override syntax.
- MongoDB server running on your host or at a configured reachable URI. Compass is only the GUI client.
- Internet access for the initial Docker images and Gradle dependency downloads.
- Available CPU/memory for the selected services. The full stack includes 18 Java applications plus infrastructure;
  container memory limits do not guarantee the stack fits your machine. Start a smaller phase for initial testing.
- Free host ports: gateway `8080`, Keycloak `8180`, Redis `6379`, Kafka `9092` and selected service ports.
  This launcher starts Compose-managed Redis/Kafka, not your independently running instances. Resolve port conflicts
  before deploying; do not stop unrelated applications blindly.

Host Java, Gradle and Node are **not required for Docker backend deployment**. Java 25 is needed only for direct
JVM builds/runs; Node and pnpm are needed when running the frontend separately.

### 2. Open the backend directory and review configuration

```bash
cd /Users/rabiulbiswas/Desktop/openhire-ai
docker info
docker compose version
./run-backend.sh deploy --help
```

Defaults are defined in `docker-compose.yml` and each service's `src/main/resources/application*.yml`.
This checkout does not include a root `.env.example`. If customization is needed, create a private root `.env`
with only the required overrides, using Compose variable names as the reference; do not overwrite an existing
file. Compose loads it automatically, but direct Gradle/JVM runs do not automatically load it. Shell environment
variables override Compose `.env` values. Do not commit real passwords, tokens or connection strings.

Important configuration checks:

- Docker Mongo URIs use `host.docker.internal:27017`, with a separate database name for each service. `localhost`
  inside a container means that container, not your Mac. Use `localhost:27017` only from host applications/Compass.
- `MONGODB_URI` configures identity; other services use their own variables, such as `JOB_MONGODB_URI` and
  `APPLICATION_MONGODB_URI`. Review each service's Compose environment when customizing databases.
- Keep `SECURITY_MODE=oidc`, `SPRING_PROFILES_ACTIVE=oidc` and `IDENTITY_PROVIDER=keycloak` for real sign-in.
  Local security mode deliberately bypasses normal sign-in and is not an authentication test.
- Keep client secrets, browser issuer URLs and redirect URLs consistent with Keycloak. Existing realms are not
  updated by startup import. Do not delete a populated realm to apply a client-setting change.
- Do not blindly copy direct-JVM localhost URLs into Docker overrides:
  a host model URL, if enabled, must be reachable from containers (normally `http://host.docker.internal:11434`).

### 3. Build and deploy

From the `openhire-ai` repository root:

```bash
# Build all 18 backend applications, start dependencies, verify startup and follow logs
./run-backend.sh deploy --follow

# Local trusted-fixture testing: deploy all services without ClamAV and follow combined logs
./run-backend.sh deploy --no-scan --follow

# Apply no-scan mode only to the resume processor
./run-backend.sh deploy --no-scan --follow resume-processor

# Rebuild only selected applications; other business dependencies must already be running
./run-backend.sh deploy identity-service api-gateway

# Deploy without attaching to logs
./run-backend.sh deploy --no-follow

# Use existing application images without rebuilding
./run-backend.sh deploy --no-build job-service

# Reattach to logs without deploying
./run-backend.sh deploy --logs-only
# Or follow only selected applications
./run-backend.sh deploy --logs-only job-service api-gateway
```

The direct entry point is `bash scripts/deployment/deploy-local.sh`. It uses the existing Gradle Dockerfile,
builds serially and follows timestamped raw logs with stack traces and 200 initial lines per service.
Logs remain combined in the same console after deployment; no extra terminal windows or tmux are needed.
The watcher reconnects after three seconds if its stream ends; recent lines may repeat on reconnection.
`--follow` overrides `OPENHIRE_FOLLOW_LOGS=0`. `--no-follow` disables watching, and `--logs-only`
watches existing applications without building, restarting or stopping anything. A quiet console can simply
mean the applications have not emitted new messages.
Ctrl+C while watching logs leaves the containers running. Set `OPENHIRE_LOG_TAIL`,
`OPENHIRE_STARTUP_TIMEOUT` or `OPENHIRE_DOCKER_PROJECT` as needed; `--help` lists all options.
Build failures stop before deployment; startup failures print container status and recent application/infrastructure
logs and exit nonzero. Successful health checks are not a substitute for workflow acceptance tests.

The script preserves infrastructure containers and named volumes; it does not remove images, prune Docker,
run `down`, add a MongoDB container, start an LLM, change Docker memory, or deploy the frontend/Prometheus.
MongoDB must already be running at the configured URI. Required Keycloak/Redis/Kafka and, when selected,
scanner/parser/mail infrastructure are started if needed. Existing infrastructure is not recreated, so configuration
changes to it need a separate reviewed action. Selected application containers are recreated even if their image tag
is unchanged. Live model-dependent features still need an external configured model; this script does not implement
a no-LLM feature mode. Running all applications requires sufficient machine resources.

#### Local resume testing without ClamAV

`--no-scan` loads `docker-compose.local-no-scan.yml`, sets `RESUME_SCAN_ENABLED=false` and activates
the resume processor's `local-no-scan` profile. It skips ClamAV startup and stops the existing ClamAV
container only after a selected resume-processor deployment passes startup checks. Signature data is retained.
If resume-processor is not selected, its running configuration and ClamAV are not changed.

Use **trusted synthetic resumes only**. File validation and extraction remain enabled, but malware scanning
is bypassed and recorded as `SKIPPED_LOCAL_DEV`, never `PASSED`. This mode cannot validate antivirus
protection and must not be used in shared/production deployments. It does not disable OIDC authentication.
Existing failed uploads are not automatically retried by this configuration change.

Pass `--no-scan` on every deployment that should retain this mode. To restore normal scanning:

```bash
./run-backend.sh deploy --follow resume-processor
```

That starts the scanner dependency and recreates the resume processor with scanning enabled by default
(remove any separate `RESUME_SCAN_ENABLED=false` environment override). Previously skipped uploads
are not retroactively scanned. See the [ClamAV guide](infra/clamav/README.md) for direct Compose commands
and signature maintenance. No extra manual `stop clamav` command is needed when using `--no-scan`.

### 4. Check startup and open the application

```bash
./run-backend.sh status
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8180/realms/openhire-staff/.well-known/openid-configuration
```

Default endpoints (adjust for your port overrides):

| Component | Local address | Notes |
| --- | --- | --- |
| Backend gateway | http://localhost:8080 | Browser API/BFF entry point, not the React UI |
| Keycloak | http://localhost:8180 | Authentication provider |
| Mailpit | http://localhost:8025 | Local test inbox when dispatch infrastructure is started |
| Integration hub health | http://localhost:8107/actuator/health | Does not prove a provider is connected |

The backend command does not start the frontend. It is a separate repository, locally at
`/Users/rabiulbiswas/Desktop/openhire-frontend`. Install its dependencies and start console/auth in separate terminals:

```bash
cd /Users/rabiulbiswas/Desktop/openhire-frontend
pnpm install --frozen-lockfile
pnpm dev
# In another terminal in that same repository:
pnpm dev:auth
```

Use backend-connected mode rather than mock APIs when reviewing authentication/workflows. Default console and
auth addresses are `http://localhost:5173` and `http://localhost:5176`. Gateway `STAFF_UI_URL`, `AUTH_UI_URL`,
candidate/partner URLs and Keycloak redirect origins must agree with the apps you run. Browser API traffic should
go through the gateway; the browser receives an HttpOnly session cookie, not access/refresh tokens.

### 5. Follow logs, redeploy and stop

```bash
# Show more startup history and allow a longer health-check wait
OPENHIRE_LOG_TAIL=500 OPENHIRE_STARTUP_TIMEOUT=600 ./run-backend.sh deploy job-service

# Inspect selected logs without rebuilding or restarting anything
./run-backend.sh logs identity-service api-gateway

# Redeploy changed source (restart alone does not rebuild an image)
./run-backend.sh deploy job-service

# Stop all containers in this project, including profiled services, retaining containers/data
docker compose --project-name openhire --profile '*' stop
```

Use your configured project name instead of `openhire` if overridden. Ctrl+C during the log-follow stage stops only
the log viewer. Interrupting earlier build/startup stages may leave a partial deployment: inspect status before retrying.
Do not run `down -v`, volume pruning or database deletion as routine troubleshooting. Container writable-layer data
is not a substitute for named volumes or backups; infrastructure recreation must be reviewed separately.

### 6. Troubleshooting

| Symptom | What to check |
| --- | --- |
| Docker daemon unavailable | Start Docker Desktop and wait for `docker info` to succeed. |
| Build fails | Read the first Gradle/compiler error in the build output. No startup is attempted after a failed build. |
| Mongo connection refused | Confirm the host Mongo server, container-reachable address, authentication and each service URI. Boot 4 uses `spring.mongodb.uri`. |
| Port already allocated | Identify the existing listener or other Compose project; change the appropriate port and dependent public URLs, or intentionally stop only the conflicting process. |
| ClamAV unhealthy / exit 137 | Inspect scanner logs and memory pressure; initial signature downloads may delay startup. Reduce simultaneous workloads. Only for trusted synthetic local fixtures, use the explicit `--no-scan` mode above; never bypass scanning for real uploads or production. |
| Startup timeout | Inspect the printed service status and recent logs. Increase timeout only for genuinely slow startup, not persistent failures. |
| Missing service after selective deployment | Named-service deployment does not start other business services. Start the required phase or full stack first. |
| Old behavior after source edits | Use `deploy <service>` to rebuild/recreate. `--no-build` intentionally uses the existing image. |
| Kafka coordinator unavailable / disconnects | Check current consumer lag and broker health before changing timeouts. Rebalances followed by stable groups can be normal recovery. Local Kafka probes use a separate 16–64 MB heap every 30 seconds to avoid inheriting the broker heap within the 768 MB limit. Earlier restart counts do not identify their cause. |
| Gateway `creationTime key must not be null` | Rebuild/recreate `api-gateway` to load the incomplete-session recovery customizer, then sign in again. Sessions missing required Redis metadata are treated as absent, without restoring their authentication or clearing unrelated Redis data. Do not use `FLUSHALL`/`FLUSHDB`. Redis outages and other corruption still require investigation. |
| Sign-in/redirect failure | Start the auth UI, check configured UI/issuer URLs and Keycloak client settings, and inspect gateway/identity logs. |
| AI unavailable | No model is started by this launcher. Manual workflows and fallback behavior need separate validation; do not expect AI drafts without a model. |

On failed startup the launcher exits nonzero, prints diagnostics and retains containers for inspection; it does not
roll back a partial deployment automatically. Logs may contain sensitive application information: redact before sharing.

## Optional phase-based startup

These older commands start the indicated phase groups. Unlike `deploy`, they do not automatically attach to live logs;
run `./run-backend.sh logs` afterward. `phase8` starts only integration-hub and assumes existing dependencies.

```bash
./run-backend.sh            # Phases 0 to 2
./run-backend.sh phase3     # adds intake, candidates and resume processing
./run-backend.sh phase4     # adds interviews, communication and delivery (and Mailpit)
./run-backend.sh phase5     # adds offers and employees
./run-backend.sh phase6     # adds AI gateway/workers and talent; AI features need a model
./run-backend.sh phase7     # adds compliance and analytics
./run-backend.sh phase8     # integration-hub only; does not rebuild the gateway
./run-backend.sh monitoring # optional Prometheus; existing analytics service required
```

The phase-based `start_stack` path uses `--remove-orphans`; prefer `deploy` for targeted updates while preserving
other phase containers. No memory capacity guarantee is implied by the Compose limits.

## Local build and test — step by step

These commands run from the standalone backend repository root, where `settings.gradle` and `gradlew` live.
They do not deploy services. Use a development/test MongoDB instance, never production: acceptance tests create
temporary databases and delete their fixture databases during cleanup.

### 1. Check prerequisites

Install JDK 25; the checked-in wrapper downloads Gradle 9.2.1, so a separate Gradle installation is unnecessary.
The first build needs internet access for dependencies. Node 22 and Helm are needed only for pipeline helper checks.
Docker is needed for Testcontainers-based infrastructure tests. MongoDB and Redis are needed for enabled
integration/context tests; a full running application stack is not needed for the resume recovery fixture.

```bash
cd /Users/rabiulbiswas/Desktop/openhire-ai
java -version

# If the wrong JDK is selected on macOS (after installing JDK 25):
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
bash gradlew --version
```

### 2. Check formatting, test and build one service

Replace `resume-processor` with another module name to target that service. Gradle builds required shared libraries.

```bash
bash gradlew :resume-processor:spotlessCheck :resume-processor:test :resume-processor:bootJar --no-daemon

# Optional: fix formatting if spotlessCheck reports differences (modifies Java files)
bash gradlew :resume-processor:spotlessApply --no-daemon

# Build the same service's dependency inventory used by CI
bash gradlew :resume-processor:cyclonedxBom --no-daemon --no-configuration-cache
```

The executable JAR is `resume-processor/build/libs/resume-processor.jar`. Without the environment flag below,
Mongo-gated acceptance tests are skipped; a successful default test run does not prove recovery acceptance.

### 3. Run resume recovery acceptance with MongoDB

Start your local MongoDB server first; Compass is a client, not a database server. The fixture starts its own
scanner and extraction servers, so ClamAV, Tika, Redis, Kafka and the deployed resume service are not required
for this specific test class. Keep the trailing slash on the base Mongo URI for compatibility with other tests.

```bash
OPENHIRE_RUN_MONGO_TESTS=true \
OPENHIRE_TEST_MONGO_URI=mongodb://localhost:27017/ \
bash gradlew :resume-processor:test \
  --tests 'com.openhire.resumeprocessor.ResumeRecoveryAcceptanceTest' \
  --rerun-tasks --no-daemon

# Run every resume-processor test, including Mongo acceptance
OPENHIRE_RUN_MONGO_TESTS=true \
OPENHIRE_TEST_MONGO_URI=mongodb://localhost:27017/ \
bash gradlew :resume-processor:test --rerun-tasks --no-daemon
```

`--rerun-tasks` prevents a previous test result from being reused after changing environment flags. Allow time
for the intentional scanner timeout (30 seconds). The tests verify quarantine, automatic retry backoff,
recovery, OCR fallback, malware rejection and retry exhaustion. Do not change retries to fail immediately
or disable tests to make CI pass. The JVM class-data-sharing warning is not itself a test failure.

### 4. Check the full backend workspace

```bash
# Formatting, default tests and executable JARs for all applicable modules
bash gradlew spotlessCheck test bootJar --no-daemon --max-workers=2

# Shared-library tests (Docker must be running for infrastructure smoke coverage)
docker info
bash gradlew :platform-starter:test :service-support:test --rerun-tasks --no-daemon
```

The default workspace run does not enable all environment-gated acceptance tests. To include Mongo-gated tests,
provide the same `OPENHIRE_RUN_MONGO_TESTS=true` and `OPENHIRE_TEST_MONGO_URI` variables as above, use
`--rerun-tasks`, and supply each test suite's required dependencies. Live LLM, external-provider and disaster
recovery acceptance require additional setup; the default build is not a substitute for those checks.

### 5. Validate CI helpers without building Java

```bash
node --test .github/scripts/*.test.mjs scripts/testing/deploy-local.test.mjs scripts/testing/mongo-recovery.test.mjs
bash -n run-backend.sh scripts/deployment/deploy-local.sh
helm lint infra/helm/openhire --set image.tag=local-validation
helm template openhire infra/helm/openhire --set image.tag=local-validation > /dev/null
```

Deployment helper tests use fake commands; they do not contact AWS or deploy workloads.

### 6. Read failures and rerun

```bash
# macOS: open the resume test report
open resume-processor/build/reports/tests/test/index.html

# Add diagnostics when reproducing a failure
bash gradlew :resume-processor:test --rerun-tasks --stacktrace --info --no-daemon
```

For Mongo-gated failures, keep the environment variables from step 3 on the diagnostic command too.
Each module writes HTML reports to `<module>/build/reports/tests/test/index.html` and XML results to
`<module>/build/test-results/test/`. In GitHub Actions, download `reports-<service>` for the failing job.
**CI gate** fails when a required service fails; fix the first test/build error rather than bypassing the gate.
Commit and push fixes, then run CI against the new commit rather than re-running an old failed commit.

## Optional direct Java development

Use Java 25 and the Gradle wrapper only when building/running outside Docker. MongoDB, Redis, Kafka and Keycloak must
be reachable at host URLs, and required service-specific configuration must be supplied. Do not run host JVMs on the
same ports as their Docker equivalents. These examples are not a complete stack launcher:

```bash
./gradlew test bootJar
./gradlew :identity-service:bootRun
# In a separate terminal, with OIDC and dependency configuration supplied:
./gradlew :api-gateway:bootRun
```

## Local demonstration accounts

Automatic sample seeding is disabled by default. All service application configurations and Compose use an
empty tenant fallback; none automatically selects `demo-org` (or `demo-tenant`). OIDC requests derive the
tenant from verified identity/portal context, and onboarding generates each real tenant's stable ID.

For an explicitly selected local fixture mode only, supply `DEFAULT_TENANT_ID` (use `OPENHIRE_DEFAULT_TENANT`
for job-service when running it outside Compose). Without an explicit tenant, local BFF fixture sessions remain
unauthenticated and tenant-required requests are rejected. Anonymous `/api/v1/public/consent` requests must
supply `tenantId`; authenticated requests use the verified token tenant. There is no automatic demo fallback.
Tenant-specific Prometheus samples require an explicit `PROMETHEUS_TENANTS` list; global samples remain enabled.

To deliberately enable the old identity fixtures, set `SAMPLE_DATA_ENABLED=true` and an explicit
`DEFAULT_TENANT_ID`; identity-service then idempotently creates fixture roles and users. This does not provision
real tenant login credentials. Legacy matching accounts in the local Keycloak realm import are fixture-only:

- `admin@openhire.local` / `OpenHire-Admin-2026!`
- `recruiter@openhire.local` / `OpenHire-Recruiter-2026!`
- `admin@second.openhire.local` / `OpenHire-Second-2026!`

These are development-only fixtures, not production credentials. Disable demo seeding and replace imported
fixture users/secrets before any shared or production deployment. Compose's Keycloak admin and service-secret
defaults are also for local use; changing `.env` does not rotate credentials already stored in an imported realm.

Logout uses `/bff/logout`: it revokes the Redis session, closes the Keycloak SSO session, and redirects to the
configured authentication page. Account profile and session management are available at `/api/v1/me` and
`/api/v1/me/sessions`.

## Swagger / OpenAPI service dropdown

The gateway provides an opt-in, staff-authenticated, browse-only Swagger portal for all 18 services.
See the [OpenAPI portal guide](docs/OPENAPI_PORTAL.md) for security, configuration and verification.

```bash
# Explicit local rebuild/recreate; documentation stays disabled unless enabled
OPENHIRE_API_DOCS_ENABLED=true ./run-backend.sh deploy
```

Then open `http://localhost:8080/swagger-ui/index.html` and sign in with a staff account. Keep OIDC mode enabled;
candidate sessions cannot access the portal. A portal-started login returns to Swagger. Each selected service must
be running with documentation enabled. Try it out is intentionally disabled. Keep documentation off in production.

## GitHub CI/CD

The active backend workflow is [.github/workflows/ci.yml](.github/workflows/ci.yml), displayed as **OpenHire CI**.
It runs on pushes to `main`, pull requests targeting `main`, and manual dispatch. Changed services and shared-library
dependents are built/tested; a manual run checks all services. Frontend CI lives in its own repository.

CI runs formatting, Java tests, JAR/SBOM creation, helper tests and Helm validation. Mongo/Redis fixtures and
Testcontainers support enabled integration tests; live LLM/provider checks are separate. **CI gate** is the aggregate
required check. Reports are uploaded even on failures when available; inspect them before rerunning.

Image publication, release assembly and deployment are **hard-disabled in workflow code**, including manual
deployment runs. Setting an AWS variable alone cannot enable them. No AWS credentials are needed for current CI.
The [pipeline guide](.github/README.md) describes the retained future ECR/EKS path; it is not active deployment.
GitHub CI does not change your running local containers; use the explicit local deployment commands above.

### Security, operations and API references

- Use [service designs and endpoint references](docs/services/README.md) with [contracts](contracts/) when integrating
  clients. They include public, staff and internal boundaries; do not assume a documented endpoint is anonymous.
- Check generated endpoint documentation without running services: `node scripts/maintenance/service-reference.mjs --check`.
- Phase 7 covers monitoring, privacy and retention; Phase 8 covers integrations and recovery. Read the relevant
  phase/service guide before enabling a destructive retention job, restoration or live provider connection.
- Optional Prometheus is available through `./run-backend.sh monitoring` at `http://localhost:9090` by default.
  Its configured container cap is not a guarantee that the whole stack fits your host.
- Local Compose publishes service ports and uses development security settings (including a non-secure session
  cookie). Do not expose this local stack directly to the internet or treat it as production deployment configuration.

### Local verification

```bash
curl --fail http://localhost:8080/actuator/health

# Launcher tests use fake Docker commands; they do not deploy services (Node required)
node --test scripts/testing/deploy-local.test.mjs
```

Business APIs require authenticated sessions/tokens and applicable CSRF protection. An arbitrary `X-Tenant-Id`
header does not authenticate a user. Follow the phase-specific acceptance scripts against authorized test data;
health checks alone do not prove application workflow correctness.

Review and acceptance records:

- [Phase 0 engineering foundation](docs/PHASE_0.md)
- [Phase 1 identity and gateway](docs/PHASE_1.md)
- [Phase 2 jobs and approvals](docs/PHASE_2.md)
- [Phase 3 intake, candidates and resumes](docs/PHASE_3.md)
- [Phase 4 interviews, messaging and delivery](docs/PHASE_4.md)
- [Phase 5 offers, hire saga and employees](docs/PHASE_5.md)
- [Phase 6 local AI, talent and integrity](docs/PHASE_6.md)
- [Phase 7 compliance and operations](docs/PHASE_7.md)
- [Phase 8 enterprise integrations](docs/PHASE_8.md)
- [Remaining work by phase](docs/PHASE_IMPLEMENTATION_GAPS.md)
- [All service designs and endpoint references](docs/services/README.md)

Detailed service documentation:

- [Identity service](docs/services/identity-service.md)
- [API gateway](docs/services/api-gateway.md)
- [Job service](docs/services/job-service.md)
- [Approval service](docs/services/approval-service.md)
- [Application service](docs/services/application-service.md)
- [Candidate service](docs/services/candidate-service.md)
- [Resume processor](docs/services/resume-processor.md)
- [Interview service](docs/services/interview-service.md)
- [Communication service](docs/services/communication-service.md)
- [Notification dispatcher](docs/services/notification-dispatcher.md)
- [Offer service](docs/services/offer-service.md)
- [Employee service](docs/services/employee-service.md)
- [AI gateway](docs/services/ai-gateway.md)
- [AI workers](docs/services/ai-workers.md)
- [Talent service](docs/services/talent-service.md)
- [Compliance service](docs/services/compliance-service.md)
- [Analytics service](docs/services/analytics-service.md)
- [Integration hub](docs/services/integration-hub.md)
