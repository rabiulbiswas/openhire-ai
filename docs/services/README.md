# Service design and REST documentation

Source checkpoint: **2026-10-16**. Covers all **18 runnable backend modules** in `backend/settings.gradle`, plus two shared libraries. These documents describe implemented code and distinguish planned/unverified behavior; they do not certify every phase for production.

Start with [end-to-end platform design](END_TO_END_DESIGN.md) and [shared component purposes](SHARED_COMPONENTS.md), then read the service guide and detailed REST reference.

| Service | Business ownership | Design and components | REST details |
| --- | --- | --- | --- |
| identity-service | Organizations, members, policy, SAML and SCIM | [Design](identity-service.md) | [44 operations](identity-service-endpoints.md) |
| api-gateway | Browser session and external API boundary | [Design](api-gateway.md) | [8 owned operations](api-gateway-endpoints.md) |
| job-service | Requisition versions and publication | [Design](job-service.md) | [24 operations](job-service-endpoints.md) |
| approval-service | Decisions, inbox and delegation | [Design](approval-service.md) | [13 operations](approval-service-endpoints.md) |
| application-service | Intake, pipeline and human decisions | [Design](application-service.md) | [35 operations](application-service-endpoints.md) |
| candidate-service | Candidate identity and reviewed merges | [Design](candidate-service.md) | [8 operations](candidate-service-endpoints.md) |
| resume-processor | Scan, extraction and clean download | [Design](resume-processor.md) | [4 operations](resume-processor-endpoints.md) |
| interview-service | Scheduling, scorecards and debrief | [Design](interview-service.md) | [20 operations](interview-service-endpoints.md) |
| offer-service | Terms, approvals and candidate responses | [Design](offer-service.md) | [19 operations](offer-service-endpoints.md) |
| employee-service | Hire saga, onboarding and rewards | [Design](employee-service.md) | [10 operations](employee-service-endpoints.md) |
| ai-gateway | Model calls, guardrails, prompts and usage | [Design](ai-gateway.md) | [18 operations](ai-gateway-endpoints.md) |
| ai-workers | Matching and integrity signals | [Design](ai-workers.md) | [2 operations](ai-workers-endpoints.md) |
| talent-service | Consent-aware search and rediscovery | [Design](talent-service.md) | [7 operations](talent-service-endpoints.md) |
| compliance-service | Audit, privacy, retention and fairness | [Design](compliance-service.md) | [26 operations](compliance-service-endpoints.md) |
| analytics-service | Metrics, SLOs and failed-event recovery | [Design](analytics-service.md) | [14 operations](analytics-service-endpoints.md) |
| communication-service | Conversations, templates and preferences | [Design](communication-service.md) | [19 operations](communication-service-endpoints.md) |
| notification-dispatcher | Channel delivery and retries | [Design](notification-dispatcher.md) | [3 operations](notification-dispatcher-endpoints.md) |
| integration-hub | Calendars, webhooks, MCP and budgets | [Design](integration-hub.md) | [23 operations](integration-hub-endpoints.md) |

Total: **297 directly mapped controller operations**, including staff, public and internal handlers. Actuator endpoints and gateway proxy aliases are additional framework/configuration surfaces, not counted as owned handlers. The separate nineteenth `integrity-worker` from the original plan is not a current executable: integrity processing is within `ai-workers`. `platform-starter` and `service-support` are libraries.

## How to read these documents

REST references preserve full paths, binding annotations, optional inputs, query defaults, header names, declared response types, controller status annotations, access-helper calls and related record DTO fields. Dynamic map responses are not assigned invented schemas: consult the linked handler. Additional application/domain authorization remains authoritative; no extracted access helper does not mean public access.

Existing [OpenAPI/AsyncAPI contracts](../../contracts) remain available. Investigate drift against current source before changing a client. Older verification sections retain historical phase context; no application/runtime tests were rerun for this documentation task.

Diagrams use Mermaid blocks; view them in a Mermaid-capable Markdown preview. Prose explains the same flows for readers without diagram rendering. Planned adapters and acceptance gaps are explicitly labelled.

## Maintain and verify

```sh
node backend/scripts/service-reference.mjs --check
```

The read-only extractor detects stale endpoint references. `--patch --service=interview-service` emits a patch for review/application; it does not mutate files. Review business narratives when behavior changes, and keep public, staff and internal security boundaries distinct.
