# OpenHire backend

Phases 0 to 6 are implemented and runtime-accepted on the local stack; each phase document records its evidence and
the checks that still need an owner decision. Phase 3 covers application intake, candidate identity/merge and resume
processing; Phase 4 interviews, messaging and notification delivery; Phase 5 offers, the hire saga and employee
onboarding; Phase 6 a local-model AI layer for matching, drafting, talent search and integrity signals.

## Technology baseline

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.2, Gradle 9.2.1
- Host MongoDB databases `openhire_identity`, `oh_job`, and `oh_approval`
- Redis-backed BFF sessions and gateway rate limits
- Kafka identity, job and approval events
- Keycloak 26.8 for staff/candidate OIDC; isolated local identity adapter remains available for development

## Active modules

- `platform-starter`: tenant context, event envelope, RFC 9457 errors.
- `identity-service`: organizations, users, RBAC, invitations, API keys, SSO/auth policy, setup, quotas, and break-glass requests.
- `api-gateway`: BFF session boundary, OIDC entry point, tenant propagation, routing, and Redis token-bucket limiting.
- `job-service`: immutable job versions, lifecycle, hiring configuration, idempotency, outbox and local-LLM drafting.
- `approval-service`: sequential/parallel approvals, assigned decisions, delegation, SLA reminders and outbox events.
- `application-service`: multichannel intake, imports, screening, pipeline and atomic Mongo outbox (8084).
- `candidate-service`: identity resolution, provenance, Candidate 360 and reviewed merges (8085).
- `resume-processor`: quarantined uploads, ClamAV scanning, Tika/OCR and safe retrieval (8086).
- `service-support`: shared workflow security, indexes, internal clients and outbox (library, not a service).

## Architecture

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
cd /Users/rabiulbiswas/Desktop/openhire-ai/backend
docker info
docker compose version
./run-backend.sh deploy --help
```

Defaults are defined in `docker-compose.yml`. If customization is needed, create a private `backend/.env` using
`.env.example` as a reference; do not overwrite an existing `.env`. Compose loads it automatically. Shell environment
variables override `.env` values. Do not commit real passwords, tokens or connection strings.

Important configuration checks:

- Docker Mongo URIs use `host.docker.internal:27017`, with a separate database name for each service. `localhost`
  inside a container means that container, not your Mac. Use `localhost:27017` only from host applications/Compass.
- `MONGODB_URI` configures identity; other services use their own variables, such as `JOB_MONGODB_URI` and
  `APPLICATION_MONGODB_URI`. Review each service's Compose environment when customizing databases.
- Keep `SECURITY_MODE=oidc`, `SPRING_PROFILES_ACTIVE=oidc` and `IDENTITY_PROVIDER=keycloak` for real sign-in.
  Local security mode deliberately bypasses normal sign-in and is not an authentication test.
- Keep client secrets, browser issuer URLs and redirect URLs consistent with Keycloak. Existing realms are not
  updated by startup import. Do not delete a populated realm to apply a client-setting change.
- The example environment includes direct-JVM localhost URLs. Do not blindly copy those into Docker overrides:
  a host model URL, if enabled, must be reachable from containers (normally `http://host.docker.internal:11434`).

### 3. Build and deploy

From `openhire-ai/backend`:

```bash
# Build all 18 backend applications, start dependencies, verify startup and follow logs
./run-backend.sh deploy

# Rebuild only selected applications; other business dependencies must already be running
./run-backend.sh deploy identity-service api-gateway

# Deploy without attaching to logs
./run-backend.sh deploy --no-follow

# Use existing application images without rebuilding
./run-backend.sh deploy --no-build job-service

# Reattach to logs without deploying
./run-backend.sh logs job-service api-gateway
```

The direct entry point is `bash scripts/deploy-local.sh`. It uses the existing Gradle Dockerfile,
builds serially and follows timestamped raw logs with stack traces and 200 initial lines per service.
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

The backend command does not start the frontend. Follow the [frontend README](../frontend/README.md) and run the
console/auth applications from `openhire-ai/frontend` in separate terminals. Use its backend-connected mode rather
than mocks when reviewing real authentication or workflows. All browser API traffic should go through the gateway;
the browser receives an HttpOnly session cookie, not access/refresh tokens.

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
| ClamAV unhealthy / exit 137 | Inspect scanner logs and memory pressure; initial signature downloads may delay startup. Reduce simultaneous workloads; never bypass scanning. |
| Startup timeout | Inspect the printed service status and recent logs. Increase timeout only for genuinely slow startup, not persistent failures. |
| Missing service after selective deployment | Named-service deployment does not start other business services. Start the required phase or full stack first. |
| Old behavior after source edits | Use `deploy <service>` to rebuild/recreate. `--no-build` intentionally uses the existing image. |
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

At identity-service startup, the application idempotently creates the `demo-org` workspace, `ORG_ADMIN` and
`RECRUITER` roles, and sample users `admin@openhire.local` and `recruiter@openhire.local`. Set
`SAMPLE_DATA_ENABLED=false` to disable this outside local development. These are identity records, not password
credentials; matching interactive demo accounts are imported into Keycloak for local use:

- `admin@openhire.local` / `OpenHire-Admin-2026!`
- `recruiter@openhire.local` / `OpenHire-Recruiter-2026!`
- `admin@second.openhire.local` / `OpenHire-Second-2026!`

Logout uses `/bff/logout`: it revokes the Redis session, closes the Keycloak SSO session, and redirects to the
configured authentication page. Account profile and session management are available at `/api/v1/me` and
`/api/v1/me/sessions`.

## GitHub CI/CD

See the [pipeline setup and deployment guide](../.github/README.md) for affected-service builds, tests, ECR publication,
approval-gated EKS releases and the GitHub Actions pipeline UI. CI runs without AWS; publication/deployment remain
disabled until explicitly configured. Runtime secrets are not stored in the workflow.

### Local verification

```bash
curl --fail http://localhost:8080/actuator/health

# Launcher tests use fake Docker commands; they do not deploy services (Node required)
node --test scripts/deploy-local.test.mjs
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
- [Phase 3 acceptance](docs/PHASE_3.md)
- [Phase 2 progress](docs/PHASE_2.md)
