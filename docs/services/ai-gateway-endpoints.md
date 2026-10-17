# ai-gateway — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](ai-gateway.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **18**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## InternalAiController

Source: [InternalAiController](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java).

### POST `/api/v1/internal/ai/embed`

- Handler: `embed` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L41)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.embed`.

Request bindings and declared response:

```java
EmbedResponse embed( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody EmbedRequest request)
```

### POST `/api/v1/internal/ai/judge`

- Handler: `judge` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L49)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.judge`.

Request bindings and declared response:

```java
JudgeResponse judge( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody JudgeRequest request)
```

### POST `/api/v1/internal/ai/structure-resume`

- Handler: `structure` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L57)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.structureResume`.

Request bindings and declared response:

```java
Map<String, Object> structure( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody StructureRequest request)
```

### POST `/api/v1/internal/ai/jd-draft`

- Handler: `jdDraft` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L65)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.jdDraft`.

Request bindings and declared response:

```java
Map<String, Object> jdDraft( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody JdRequest request)
```

### POST `/api/v1/internal/ai/message-draft`

- Handler: `message` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L73)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.messageDraft`.

Request bindings and declared response:

```java
MessageResponse message( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody MessageRequest request)
```

### POST `/api/v1/internal/ai/nl-filter`

- Handler: `nlFilter` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L81)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.nlFilter`.

Request bindings and declared response:

```java
Map<String, Object> nlFilter( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody NlRequest request)
```

### POST `/api/v1/internal/ai/explain-fit`

- Handler: `explain` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/InternalAiController.java#L89)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `tasks.explainFit`.

Request bindings and declared response:

```java
ExplainResponse explain( @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant, @RequestBody ExplainRequest request)
```

## LogRetentionController

Source: [LogRetentionController](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/LogRetentionController.java).

### GET `/api/v1/internal/ai/log-retention/tenants`

- Handler: `tenants` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/LogRetentionController.java#L26)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
Map<String, Object> tenants(@RequestHeader("X-Internal-Service-Secret") String secret)
```

### POST `/api/v1/internal/ai/log-retention`

- Handler: `run` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/LogRetentionController.java#L32)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
Map<String, Object> run(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody Input input)
```

## StaffAiController

Source: [StaffAiController](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java).

### POST `/api/v1/jobs/ai-draft`

- Handler: `draft` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L106)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, DRAFTERS)`.
- Delegated operations: `tasks.staffDraft`.

Request bindings and declared response:

```java
DraftResponse draft(Authentication auth, @RequestBody JdRequest request)
```

### GET `/api/v1/ai-settings`

- Handler: `settings` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L111)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, ADMINS)`.
- Delegated operations: `settings.view`.

Request bindings and declared response:

```java
SettingsView settings(Authentication auth)
```

### PUT `/api/v1/ai-settings`

- Handler: `updateSettings` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L116)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, ADMINS)`; `access.actor(auth)`.
- Delegated operations: `settings.update`.

Request bindings and declared response:

```java
SettingsView updateSettings(Authentication auth, @RequestBody UpdateInput input)
```

### GET `/api/v1/prompts`

- Handler: `prompts` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L122)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, PROMPT_READERS)`.
- Delegated operations: `prompts.list`.

Request bindings and declared response:

```java
List<PromptSummary> prompts(Authentication auth)
```

### GET `/api/v1/prompts/{id}`

- Handler: `prompt` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L127)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, PROMPT_READERS)`.
- Delegated operations: `prompts.get`.

Request bindings and declared response:

```java
PromptDetail prompt(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/prompts/{id}/evaluate`

- Handler: `evaluate` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L132)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `require(auth, ADMINS)`; `access.actor(auth)`.
- Delegated operations: `evaluation.start`.

Request bindings and declared response:

```java
ResponseEntity<EvalRunView> evaluate(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/eval-runs/{id}`

- Handler: `evalRun` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L138)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, ADMINS)`.
- Delegated operations: `evaluation.get`.

Request bindings and declared response:

```java
EvalRunView evalRun(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/prompts/{id}/promote`

- Handler: `promote` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L143)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, ADMINS)`; `access.actor(auth)`.
- Delegated operations: `prompts.promote`.

Request bindings and declared response:

```java
PromptSummary promote( Authentication auth, @PathVariable String id, @RequestBody(required = false) PromoteInput input)
```

### GET `/api/v1/ai/ops-summary`

- Handler: `summary` ([source](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java#L150)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `require(auth, ADMINS)`.
- Delegated operations: `ops.summary`.

Request bindings and declared response:

```java
OpsService.Summary summary(Authentication auth, @RequestParam(defaultValue = "24h") String range)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### Input

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/LogRetentionController.java)

```java
record Input(String tenantId, String cutoff, boolean dryRun, int limit)
```

### PromoteInput

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java)

```java
record PromoteInput(Integer trafficPct)
```

### EvalRunView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/StaffAiController.java)

```java
record EvalRunView(
            String id,
            String promptId,
            String baseline,
            String model,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            List<Check> checks,
            boolean passed,
            int casesRun,
            int casesTotal,
            List<CaseResult> cases,
            String error)
```

### EmbedRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record EmbedRequest(List<String> inputs, String inputType)
```

### EmbedResponse

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record EmbedResponse(List<float[]> vectors, int dimensions, Provenance provenance)
```

### JudgeRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record JudgeRequest(
            CriterionIn criterion, List<ChunkIn> chunks, String rubricVersion, String candidateName)
```

### JudgeResponse

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record JudgeResponse(
            String criterionId,
            int score,
            List<EvidenceView> evidence,
            List<String> strengths,
            List<String> gaps,
            String rationale,
            boolean insufficientEvidence,
            GuardrailsView guardrails,
            Provenance provenance)
```

### StructureRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record StructureRequest(String text, String candidateName)
```

### JdRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record JdRequest(
            String title, String team, String level, String location, List<String> mustHaveSkills, String tone)
```

### MessageRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record MessageRequest(
            String purpose,
            String candidateFirstName,
            String jobTitle,
            List<ThreadItem> thread,
            String notes,
            String channel)
```

### MessageResponse

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record MessageResponse(String text, Provenance provenance)
```

### NlRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record NlRequest(String query)
```

### ExplainRequest

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record ExplainRequest(JobIn job, List<ChunkIn> historical, List<ChunkIn> current)
```

### ExplainResponse

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record ExplainResponse(
            String whyFitNow,
            String historicalEvidence,
            List<StatementView> whyFitNowStatements,
            List<StatementView> historicalStatements,
            Provenance provenance)
```

### DraftResponse

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record DraftResponse(
            String description,
            List<String> biasFlags,
            List<Map<String, Object>> biasFlagDetails,
            List<Map<String, Object>> suggestedInterviewPlan,
            List<Map<String, Object>> rubric,
            List<Map<String, Object>> knockoutQuestions,
            Provenance provenance)
```

### Summary

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/OpsService.java)

```java
record Summary(
            String range,
            int assessmentsCompleted,
            double providerErrorRatePct,
            double fallbackToRulesPct,
            double guardrailRejectionsPct,
            List<TaskLine> byTask,
            List<Circuit> circuits)
```

### PromptSummary

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/PromptAdminService.java)

```java
record PromptSummary(
            String id,
            String key,
            int version,
            PromptStatus status,
            int trafficPct,
            String owner,
            EvalStatus evalStatus,
            String evalRunId,
            Instant updatedAt)
```

### PromptDetail

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/PromptAdminService.java)

```java
record PromptDetail(
            String id,
            String key,
            int version,
            PromptStatus status,
            int trafficPct,
            String owner,
            EvalStatus evalStatus,
            String evalRunId,
            Instant updatedAt,
            String systemTemplate,
            String userTemplate,
            Map<String, Object> outputSchema)
```

### UpdateInput

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/SettingsService.java)

```java
record UpdateInput(
            Boolean localOnly,
            Map<String, TaskIn> tasks,
            Double monthlyBudgetInr,
            Boolean showScoreToHiringManagers,
            Boolean blindReviewDefault)
```

### SettingsView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/SettingsService.java)

```java
record SettingsView(
            boolean localOnly,
            Map<String, TaskView> tasks,
            double monthlyBudgetInr,
            double usedInr,
            boolean showScoreToHiringManagers,
            boolean blindReviewDefault,
            List<ProviderView> providers)
```

### Provenance

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/domain/AiTypes.java)

```java
record Provenance(
            String requestId,
            String task,
            String provider,
            String model,
            String promptKey,
            Integer promptVersion,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            double costInr,
            boolean cached,
            String traceId,
            Instant createdAt)
```

### CaseResult

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/domain/EvalRun.java)

```java
record CaseResult(
            String caseId,
            boolean valid,
            Integer score,
            int min,
            int max,
            boolean inBand,
            int quotesTotal,
            int quotesGrounded,
            long latencyMs)
```

### Check

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/domain/EvalRun.java)

```java
record Check(String name, Double baseline, double candidate, String gate, boolean passed)
```

### ChunkIn

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record ChunkIn(String chunkId, String text)
```

### CriterionIn

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record CriterionIn(String id, String label, String requirement, String description)
```

### EvidenceView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record EvidenceView(String chunkId, String quote)
```

### GuardrailsView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record GuardrailsView(boolean grounded, boolean protectedAttributeFree, boolean injectionSuspected)
```

### ThreadItem

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record ThreadItem(String from, String text)
```

### JobIn

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record JobIn(String title, String summary, List<String> criteria)
```

### StatementView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/AiViews.java)

```java
record StatementView(String statement, String source, String chunkId, String quote)
```

### TaskLine

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/OpsService.java)

```java
record TaskLine(
            String task,
            String model,
            int requests,
            long p95LatencyMs,
            double errorPct,
            double cacheHitPct,
            double costInr)
```

### Circuit

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/OpsService.java)

```java
record Circuit(String provider, String state)
```

### TaskIn

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/SettingsService.java)

```java
record TaskIn(String provider, String model, ModelRef fallback)
```

### TaskView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/SettingsService.java)

```java
record TaskView(String provider, String model, ModelRef fallback)
```

### ProviderView

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/application/SettingsService.java)

```java
record ProviderView(
            String id, ProviderKind kind, String baseUrl, boolean local, ProviderStatus status, List<String> models)
```

### ModelRef

[Definition](../../ai-gateway/src/main/java/com/openhire/aigateway/domain/AiTypes.java)

```java
record ModelRef(String provider, String model)
```
