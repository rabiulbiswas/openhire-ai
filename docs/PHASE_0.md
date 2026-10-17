# Phase 0 — engineering foundation

## Delivery status

Phase 0 implementation is complete. It establishes the repeatable Java 25 / Spring Boot 4 engineering baseline used by every backend service.

## Completed scope

- Central Gradle version catalog for Spring Boot, Spring Cloud, test, formatting, SBOM and security tooling.
- Groovy `build.gradle` files across the multi-project build.
- Dependency locking for every module.
- Palantir Java formatting through Spotless.
- ArchUnit boundary tests for identity, job and approval services.
- Shared tenant, audit, event-envelope, outbox and idempotent-event components in `platform-starter`.
- Testcontainers smoke coverage for MongoDB, Redis and Kafka, opt-in when Docker is available.
- Opt-in local-LLM connectivity verification.
- CycloneDX SBOM generation and OWASP dependency scanning.
- GitHub Actions backend pipeline on Java 25.
- Docker Compose validation and shell syntax checks for the local launch and acceptance scripts.

## Patterns

| Concern | Pattern |
|---|---|
| Service boundaries | Hexagonal architecture with ports and adapters |
| Domain consistency | Aggregate roots and explicit state transitions |
| Event delivery | Transactional-outbox structure plus idempotent-consumer base |
| Cross-service contracts | Versioned OpenAPI and AsyncAPI documents |
| Tenant safety | Request-scoped tenant context and tenant-scoped repositories |
| Supply-chain controls | Locked dependencies, SBOM and vulnerability scan |

## Verification

Run from `backend`:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user" ./gradlew --no-daemon spotlessCheck test
GRADLE_USER_HOME="$PWD/.gradle-user" ./gradlew --no-daemon --no-configuration-cache cyclonedxBom
NVD_API_KEY=... GRADLE_USER_HOME="$PWD/.gradle-user" ./gradlew --no-daemon --no-configuration-cache dependencyCheckAnalyze
docker compose config --quiet
```

### Evidence — 2026-10-09

- `spotlessCheck test` passes: 75 tests, 0 failures, including the Testcontainers Mongo/Redis/Kafka smoke
  test and the opt-in real-Mongo candidate tests (`OPENHIRE_RUN_MONGO_TESTS=true`).
- `cyclonedxBom` produces one SBOM per module (`<module>/build/reports/<module>-sbom.json`, 9 files).
- Docker Compose validation and shell syntax checks pass.

### Gaps closed on 2026-10-09

- Formatting violations in approval-service, identity-service, service-support and resume-processor
  (`spotlessApply`, formatting only).
- Infrastructure smoke test pinned to `mongo:8.2`: `mongo:8.0` exits on Linux kernel 6.19+
  (MongoDB SERVER-121912), which current Docker Desktop ships.
- `cyclonedxBom` and `dependencyCheckAnalyze` are applied per module. The root-level SBOM task failed under
  Gradle 9 (unsafe cross-project resolution), and the root-level `dependencyCheckAnalyze` scanned only the
  root project, which has no dependencies — so CI was not scanning any service. CI now uploads every
  module's SBOM and report and passes the `NVD_API_KEY` secret.

### Still open

- Dependency-vulnerability scan has not been run: it needs an NVD API key (`NVD_API_KEY`, also as a CI
  secret). Without one the first NVD download takes hours and is rate-limited.
- Local-LLM connectivity test (`OPENHIRE_LLM_CONNECTIVITY_TEST=true`) is skipped: no Ollama or
  OpenAI-compatible server is installed on this machine.
- Build scripts use Groovy DSL; the implementation plan specified Kotlin DSL. Record this as an approved
  change or convert.
- The plan's non-service modules `contract-catalog`, `test-support` and `demo-seeder` do not exist as
  modules. Contracts live in `contracts/` (Phases 1–3 only, not the full later-phase endpoint inventory);
  demo data is seeded by identity-service at startup.
