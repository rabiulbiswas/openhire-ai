# talent-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](talent-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **7**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## PublicConsentController

Source: [PublicConsentController](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/PublicConsentController.java).

### GET `/api/v1/public/applications/{applicationId}/talent-consent`

- Handler: `get` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/PublicConsentController.java#L26)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `consent.current`.

Request bindings and declared response:

```java
ConsentView get( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

### PUT `/api/v1/public/applications/{applicationId}/talent-consent`

- Handler: `put` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/PublicConsentController.java#L34)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `consent.set`.

Request bindings and declared response:

```java
ConsentView put( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt, @RequestBody ConsentInput input)
```

## TalentController

Source: [TalentController](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java).

### GET `/api/v1/talent/rediscovery`

- Handler: `rediscovery` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java#L42)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staffTenant(auth)`.
- Delegated operations: `rediscovery.forJob`.

Request bindings and declared response:

```java
SearchResult rediscovery( Authentication auth, @RequestParam(required = false) String jobId, @RequestParam(defaultValue = "false") boolean refresh)
```

### POST `/api/v1/talent/search/nl`

- Handler: `naturalLanguage` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java#L50)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staffTenant(auth)`.
- Delegated operations: `search.naturalLanguage`.

Request bindings and declared response:

```java
SearchResult naturalLanguage(Authentication auth, @RequestBody NlInput input)
```

### POST `/api/v1/talent/search`

- Handler: `search` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java#L55)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staffTenant(auth)`.
- Delegated operations: `search.search`.

Request bindings and declared response:

```java
SearchResult search(Authentication auth, @RequestBody SearchInput input)
```

### GET `/api/v1/talent/pool-health`

- Handler: `poolHealth` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java#L60)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staffTenant(auth)`.
- Delegated operations: `search.poolHealth`.

Request bindings and declared response:

```java
PoolHealth poolHealth(Authentication auth)
```

### POST `/api/v1/talent/{candidateId}/invite`

- Handler: `invite` ([source](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java#L65)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `viewers.staffTenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `invitations.invite`.

Request bindings and declared response:

```java
void invite( Authentication auth, @PathVariable String candidateId, @RequestBody(required = false) InviteInput input)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### ConsentInput

[Definition](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/PublicConsentController.java)

```java
record ConsentInput(Boolean granted)
```

### NlInput

[Definition](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java)

```java
record NlInput(String query)
```

### SearchInput

[Definition](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java)

```java
record SearchInput(Filters filters, String semanticQuery)
```

### InviteInput

[Definition](../../talent-service/src/main/java/com/openhire/talent/adapter/in/web/TalentController.java)

```java
record InviteInput(String jobId)
```

### SearchResult

[Definition](../../talent-service/src/main/java/com/openhire/talent/application/TalentViews.java)

```java
record SearchResult(
            List<String> filters,
            Filters filterSpec,
            String semanticQuery,
            List<CandidateView> candidates,
            String rediscoveryForJobTitle,
            String jobId,
            String semanticMode)
```

### PoolHealth

[Definition](../../talent-service/src/main/java/com/openhire/talent/application/TalentViews.java)

```java
record PoolHealth(
            int silverMedalists, int consentExpiringSoon, int reEngagedThisQuarter, double hireRateFromPoolPct)
```

### ConsentView

[Definition](../../talent-service/src/main/java/com/openhire/talent/application/TalentViews.java)

```java
record ConsentView(boolean granted, String consentUpdatedAt)
```

### Filters

[Definition](../../talent-service/src/main/java/com/openhire/talent/domain/TalentTypes.java)

```java
record Filters(
            List<String> locations,
            List<String> skills,
            Integer minYears,
            Integer lastInterviewedWithinDays,
            Boolean openToRelocate,
            String stage)
```

### CandidateView

[Definition](../../talent-service/src/main/java/com/openhire/talent/application/TalentViews.java)

```java
record CandidateView(
            String id,
            String name,
            String title,
            int matchScore,
            String whyFitNow,
            String historicalEvidence,
            boolean explained)
```
