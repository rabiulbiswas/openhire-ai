# analytics-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](analytics-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **14**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## AnalyticsController

Source: [AnalyticsController](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java).

### GET `/api/v1/analytics/kpis`

- Handler: `kpis` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java#L28)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.analyticsTenant(auth)`.

Request bindings and declared response:

```java
AnalyticsKpis kpis( Authentication auth, @RequestParam(defaultValue = "30d") String range, @RequestParam(required = false) String jobId)
```

### GET `/api/v1/analytics/funnel`

- Handler: `funnel` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java#L36)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.analyticsTenant(auth)`.

Request bindings and declared response:

```java
FunnelResult funnel( Authentication auth, @RequestParam(required = false) String jobId, @RequestParam(defaultValue = "30d") String range)
```

### GET `/api/v1/analytics/time-to-hire`

- Handler: `timeToHire` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java#L44)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.analyticsTenant(auth)`.

Request bindings and declared response:

```java
List<TimeToHirePoint> timeToHire( Authentication auth, @RequestParam(defaultValue = "30d") String range, @RequestParam(required = false) String jobId)
```

### GET `/api/v1/analytics/sources`

- Handler: `sources` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java#L52)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.analyticsTenant(auth)`.

Request bindings and declared response:

```java
List<SourceQuality> sources( Authentication auth, @RequestParam(defaultValue = "30d") String range, @RequestParam(required = false) String jobId)
```

### GET `/api/v1/analytics/ai-overrides`

- Handler: `aiOverrides` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java#L60)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.analyticsTenant(auth)`.

Request bindings and declared response:

```java
List<OverrideReason> aiOverrides( Authentication auth, @RequestParam(defaultValue = "30d") String range, @RequestParam(required = false) String jobId)
```

### GET `/api/v1/analytics/stage-latency`

- Handler: `stageLatency` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/AnalyticsController.java#L68)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.analyticsTenant(auth)`.

Request bindings and declared response:

```java
List<StageLatency> stageLatency( Authentication auth, @RequestParam(defaultValue = "30d") String range, @RequestParam(required = false) String jobId)
```

## DeadLetterController

Source: [DeadLetterController](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java).

### GET `/api/v1/admin/dlq`

- Handler: `list` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java#L33)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.operator(auth)`.

Request bindings and declared response:

```java
List<DeadLetter> list(Authentication auth, @RequestParam(defaultValue = "50") int limit)
```

### GET `/api/v1/admin/dlq/{id}`

- Handler: `detail` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java#L38)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.operator(auth)`.

Request bindings and declared response:

```java
DeadLetterDetail detail(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/admin/dlq/{id}/replay`

- Handler: `replay` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java#L43)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.operator(auth)`.

Request bindings and declared response:

```java
ReplayResult replay( Authentication auth, @PathVariable String id, @RequestParam(defaultValue = "false") boolean force, @RequestBody ReplayInput input)
```

### POST `/api/v1/admin/dlq/replay`

- Handler: `replayBatch` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java#L52)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.operator(auth)`.

Request bindings and declared response:

```java
BatchResult replayBatch(Authentication auth, @RequestBody BatchInput input)
```

## PlatformController

Source: [PlatformController](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/PlatformController.java).

### GET `/api/v1/platform/services`

- Handler: `services` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/PlatformController.java#L27)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.requireSuperAdmin(auth)`.

Request bindings and declared response:

```java
List<PlatformService> services(Authentication auth)
```

### GET `/api/v1/platform/slo`

- Handler: `slo` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/PlatformController.java#L33)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.sloTenant(auth)`.

Request bindings and declared response:

```java
List<Slo> slo(Authentication auth)
```

### GET `/api/v1/platform/consumers`

- Handler: `consumers` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/PlatformController.java#L38)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.requireSuperAdmin(auth)`.

Request bindings and declared response:

```java
List<ConsumerLag> consumers(Authentication auth)
```

## SloMetricsController

Source: [SloMetricsController](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/SloMetricsController.java).

### GET `/api/v1/internal/platform/metrics`

- Handler: `metrics` ([source](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/SloMetricsController.java#L33)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
String metrics(@RequestHeader("X-Internal-Service-Secret") String secret)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### ReplayInput

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java)

```java
record ReplayInput(String reason)
```

### BatchInput

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/DeadLetterController.java)

```java
record BatchInput(String reason, String topic, Integer limit, Boolean dryRun)
```

### AnalyticsKpis

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record AnalyticsKpis(
            double medianTimeToHireDays,
            String medianTimeToHireDelta,
            double offerAcceptancePct,
            String offerAcceptanceDelta,
            double aiShortlistAgreementPct,
            String aiShortlistAgreementDelta,
            double aiCostPerScreenedApplicant,
            String aiCostDelta,
            String currency)
```

### FunnelResult

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record FunnelResult(String jobTitle, List<FunnelStage> stages)
```

### TimeToHirePoint

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record TimeToHirePoint(String x, double y)
```

### SourceQuality

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record SourceQuality(String source, long applicants, double shortlistRatePct, long hires)
```

### OverrideReason

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record OverrideReason(String label, long value)
```

### StageLatency

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record StageLatency(String stage, double medianHours, double p90Hours)
```

### DeadLetter

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/DeadLetterViews.java)

```java
record DeadLetter(
            String id,
            String originalTopic,
            String consumerGroup,
            String eventType,
            String tenantId,
            String error,
            Integer attempts,
            String firstFailedAt,
            String replayedAt,
            String replayedBy,
            String payloadPreview)
```

### DeadLetterDetail

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/DeadLetterViews.java)

```java
record DeadLetterDetail(
            String id,
            String originalTopic,
            String consumerGroup,
            String eventType,
            String tenantId,
            String error,
            Integer attempts,
            String firstFailedAt,
            String replayedAt,
            String replayedBy,
            String payloadPreview,
            Object payload,
            String deadLetterTopic,
            Integer originalPartition,
            Long originalOffset,
            String exceptionClass,
            Integer replayCount)
```

### ReplayResult

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/DeadLetterViews.java)

```java
record ReplayResult(String id, String replayedAt, String replayedBy, int replayCount)
```

### BatchResult

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/DeadLetterViews.java)

```java
record BatchResult(boolean dryRun, int matched, int replayed)
```

### PlatformService

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/PlatformViews.java)

```java
record PlatformService(
            String name,
            String status,
            double uptimePct24h,
            String lastCheckedAt,
            long responseMs,
            Long requests24h,
            Long errors24h)
```

### Slo

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/PlatformViews.java)

```java
record Slo(
            String id,
            String label,
            double target,
            String window,
            double current,
            String unit,
            String status,
            double errorBudgetRemainingPct,
            String basis,
            boolean approximate)
```

### ConsumerLag

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/PlatformViews.java)

```java
record ConsumerLag(String group, String topic, long lag, String status)
```

### FunnelStage

[Definition](../../analytics-service/src/main/java/com/openhire/analytics/application/AnalyticsViews.java)

```java
record FunnelStage(String label, long value)
```
