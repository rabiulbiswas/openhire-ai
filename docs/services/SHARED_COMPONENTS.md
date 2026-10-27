# Shared components and purpose

Libraries and infrastructure below support multiple services; they are not extra runnable microservices. A dependency does not mean every service actively uses every component.

| Component | Used for | Purpose and boundary |
| --- | --- | --- |
| Java 25 / Spring Boot 4 | Service runtime | HTTP, dependency injection, configuration and lifecycle |
| Spring MVC / WebFlux | REST services / reactive gateway | Separate use cases from browser session/routing concerns |
| Spring Security / Keycloak | JWT, roles/scopes, OIDC and SAML federation | Central authentication with service-level tenant/resource authorization |
| MongoDB | Owned state, projections, queues, outboxes and grants | Durable conditional writes and uniqueness, not cross-service database joins |
| Redis | Sessions, selected caches, locks and throttles | Shared ephemeral coordination, not durable workflow truth |
| Kafka | Commands, lifecycle/audit/delivery events | Asynchronous decoupling and replay with consumer deduplication |
| Local LLM / compatible providers | AI tasks through ai-gateway | Central guardrails/model policy/provenance; results remain advisory |
| ClamAV | Resume malware scan | Unknown/outage is not a clean result |
| Embedded Tika / Tesseract | Inside resume-processor | Extract PDF/DOC/DOCX; native OCR fallback for sparse PDFs; not a malware scanner |
| SMTP | Configured channel delivery | Transport acceptance is not recipient reading confirmation |
| Actuator / optional Prometheus | Health and operations | Measurements are not end-to-end business acceptance |
| Mounted secrets / AES-GCM | Provider credentials, grants and backups | Keep credentials out of source/API views/logs; recover keys separately |
| React Query / API client | UI reads, mutations and pagination | Render persisted backend state rather than fake local success |

## platform-starter

[Source](../../platform-starter/src/main/java/com/openhire/platform).

- Tenant context carries the resolved tenant through execution. It must be combined with identity checks; a header alone is not authorization.
- Event/audit infrastructure standardizes tenant, IDs and correlation context.
- `AuditRecord`/`AuditRecorder` provide structured audit recording; callers must omit credentials and unnecessary sensitive data.
- `GlobalExceptionHandler` maps selected validation/domain errors into problem responses. SCIM and some other adapters have protocol-specific error handling.
- It is a shared library, not a standalone deployment.

## service-support

[Source](../../service-support/src/main/java/com/openhire/support).

| Component | Purpose |
| --- | --- |
| `Access`, `WorkflowSecurity` | Role/tenant/internal-secret checks and adopting-service security |
| `ServiceClient` | Internal authenticated HTTP call support |
| `Records` | Common identity/hash/error helpers |
| `EmbeddedEventRelay`, `WorkflowIndexes` | Publish supported embedded events and establish workflow indexes |
| `WorkflowKafkaErrors` | Differentiate dependency retries from terminal/dead-letter failures |
| `DependencyErrors` | Translate supported dependency failures |
| Privacy package | Owner-service privacy participants and erasure/tombstone replay protection |

The Kafka handler treats supported database/network/5xx failures differently from validation failures. Do not swallow exceptions merely to advance offsets; failed dead-letter publication must remain observable too.

## Design patterns

| Pattern | Application | Limitation |
| --- | --- | --- |
| Ports/adapters | HTTP/Kafka/Mongo/provider boundaries | Not every module has identical package layout |
| Aggregate state machine | Job/application/interview/offer/delivery transitions | No arbitrary UI status setters |
| Optimistic concurrency | Expected-version/state updates | No serialization across service databases |
| Outbox + idempotent consumer | Committed changes and event processing | No exactly-once external side effects |
| Saga | Persisted hire workflow steps | No automatic rollback of external business actions |
| Leased queue | Calendar/webhook/matching/saga workers | A lease cannot retract an issued network request |
| Circuit breaker / bulkhead | AI provider protection | No invented fallback evidence |
| Capability token | Candidate receipt/scheduling/offer access | Not interchangeable with staff authorization |
| CQRS/projection | Analytics/talent/read views | Eventual rather than universal immediate consistency |
| Human approval | Hiring decisions and MCP writes | No autonomous model authority to change records |

Use individual `application.yml` files and [Compose](../../docker-compose.yml) for actual configuration, ports and limits. Documentation examples do not authorize starting every service, changing Docker memory, contacting live providers or deleting data. See phase guides for runtime test evidence and open acceptance work.
