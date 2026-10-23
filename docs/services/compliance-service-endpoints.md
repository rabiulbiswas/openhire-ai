# compliance-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](compliance-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **26**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## AuditController

Source: [AuditController](../../compliance-service/src/main/java/com/openhire/compliance/controller/AuditController.java).

### GET `/api/v1/audit-events`

- Handler: `list` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/AuditController.java#L32)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
AuditLedger.Page list( Authentication auth, @RequestParam(required = false) String actor, @RequestParam(required = false) String action, @RequestParam(required = false) String resourceType, @RequestParam(required = false) String resourceId, @RequestParam(required = false) String candidateId, @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor)
```

### GET `/api/v1/audit-events/verify`

- Handler: `verify` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/AuditController.java#L57)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
AuditChain.Result verify(Authentication auth)
```

### GET `/api/v1/audit-events/export`

- Handler: `export` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/AuditController.java#L62)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`; `viewers.actor(auth)`.

Request bindings and declared response:

```java
ResponseEntity<StreamingResponseBody> export( Authentication auth, @RequestParam(required = false) String from, @RequestParam(required = false) String to)
```

## PublicController

Source: [PublicController](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java).

### POST `/api/v1/public/applications/{applicationId}/privacy-requests`

- Handler: `request` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java#L43)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Map<String, Object> request( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt, @RequestBody TypeInput input)
```

### GET `/api/v1/public/applications/{applicationId}/privacy-requests`

- Handler: `requests` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java#L53)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
List<Map<String, Object>> requests( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

### GET `/api/v1/public/privacy-requests/{id}/export`

- Handler: `export` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java#L61)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Map<String, Object> export( @PathVariable String id, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

### PUT `/api/v1/public/applications/{applicationId}/self-identification`

- Handler: `putSelfIdentification` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java#L69)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Map<String, Object> putSelfIdentification( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt, @RequestBody Map<String, Object> input)
```

### GET `/api/v1/public/applications/{applicationId}/self-identification`

- Handler: `getSelfIdentification` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java#L78)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Map<String, Object> getSelfIdentification( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

### GET `/api/v1/internal/consent/active`

- Handler: `activeConsent` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java#L86)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.
- Delegated operations: `consent.active`.

Request bindings and declared response:

```java
Map<String, Object> activeConsent( @RequestHeader("X-Internal-Service-Secret") String secret, @RequestParam String tenantId, @RequestParam(defaultValue = "PROCESSING") String purpose)
```

## StaffController

Source: [StaffController](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java).

### GET `/api/v1/ai-decisions`

- Handler: `aiDecisions` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L68)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
List<Map<String, Object>> aiDecisions( Authentication auth, @RequestParam(required = false) String candidateId, @RequestParam(required = false) String applicationId, @RequestParam(required = false) String task, @RequestParam(required = false) String from, @RequestParam(defaultValue = "50") int limit)
```

### GET `/api/v1/ai-decisions/{id}`

- Handler: `aiDecision` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L80)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
Map<String, Object> aiDecision(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/trace/applications/{applicationId}`

- Handler: `trace` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L85)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
List<Map<String, Object>> trace(Authentication auth, @PathVariable String applicationId)
```

### GET `/api/v1/consent-texts`

- Handler: `consentTexts` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L92)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.
- Delegated operations: `consent.texts`.

Request bindings and declared response:

```java
List<Map<String, Object>> consentTexts(Authentication auth)
```

### POST `/api/v1/consent-texts`

- Handler: `publishConsent` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L97)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`; `viewers.actor(auth)`.
- Delegated operations: `consent.publish`.

Request bindings and declared response:

```java
Map<String, Object> publishConsent(Authentication auth, @RequestBody ConsentInput input)
```

### GET `/api/v1/consent-records`

- Handler: `consentRecords` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L104)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.
- Delegated operations: `consent.records`.

Request bindings and declared response:

```java
List<Map<String, Object>> consentRecords(Authentication auth, @RequestParam(required = false) String candidateId)
```

### GET `/api/v1/privacy/requests`

- Handler: `privacyRequests` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L111)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`.

Request bindings and declared response:

```java
List<Map<String, Object>> privacyRequests(Authentication auth, @RequestParam(required = false) String status)
```

### GET `/api/v1/privacy/requests/{id}`

- Handler: `privacyRequest` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L116)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`.

Request bindings and declared response:

```java
Map<String, Object> privacyRequest(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/privacy/requests`

- Handler: `createPrivacyRequest` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L121)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`; `viewers.actor(auth)`.

Request bindings and declared response:

```java
Map<String, Object> createPrivacyRequest(Authentication auth, @RequestBody PrivacyInput input)
```

### GET `/api/v1/legal-holds`

- Handler: `legalHolds` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L130)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`.

Request bindings and declared response:

```java
List<Map<String, Object>> legalHolds(Authentication auth, @RequestParam(required = false) String status)
```

### POST `/api/v1/legal-holds`

- Handler: `placeHold` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L135)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`; `viewers.actor(auth)`.

Request bindings and declared response:

```java
Map<String, Object> placeHold(Authentication auth, @RequestBody HoldInput input)
```

### POST `/api/v1/legal-holds/{id}/release`

- Handler: `releaseHold` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L143)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`; `viewers.actor(auth)`.

Request bindings and declared response:

```java
Map<String, Object> releaseHold(Authentication auth, @PathVariable String id, @RequestBody ReasonInput input)
```

### GET `/api/v1/retention-policies`

- Handler: `retentionPolicies` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L151)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
List<Map<String, Object>> retentionPolicies(Authentication auth)
```

### PUT `/api/v1/retention-policies/{key}`

- Handler: `updatePolicy` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L156)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`; `viewers.actor(auth)`.

Request bindings and declared response:

```java
Map<String, Object> updatePolicy(Authentication auth, @PathVariable String key, @RequestBody PolicyInput input)
```

### POST `/api/v1/retention-policies/run`

- Handler: `runRetention` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L163)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.MANAGERS)`; `viewers.actor(auth)`.

Request bindings and declared response:

```java
Map<String, Object> runRetention( Authentication auth, @RequestParam(defaultValue = "true") boolean dryRun, @RequestParam(required = false) String asOf)
```

### GET `/api/v1/reports/fairness`

- Handler: `fairnessReport` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L174)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
Map<String, Object> fairnessReport( Authentication auth, @RequestParam(required = false) String jobId, @RequestParam(defaultValue = "gender") String dimension)
```

### GET `/api/v1/compliance/overview`

- Handler: `overview` ([source](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java#L182)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.require(auth, Viewers.READERS)`.

Request bindings and declared response:

```java
Map<String, Object> overview(Authentication auth)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### TypeInput

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/controller/PublicController.java)

```java
record TypeInput(String type)
```

### ConsentInput

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java)

```java
record ConsentInput(String purpose, String locale, String text)
```

### PrivacyInput

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java)

```java
record PrivacyInput(String candidateId, String type, String reason)
```

### HoldInput

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java)

```java
record HoldInput(String candidateId, String jobId, String reason, String expiresAt)
```

### ReasonInput

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java)

```java
record ReasonInput(String reason)
```

### PolicyInput

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/controller/StaffController.java)

```java
record PolicyInput(Integer retentionMonths, Boolean enabled)
```

### Result

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/domain/AuditChain.java)

```java
record Result(boolean valid, long checked, Long brokenAtSeq)
```

### Page

[Definition](../../compliance-service/src/main/java/com/openhire/compliance/service/AuditLedger.java)

```java
record Page(List<Map<String, Object>> data, String nextCursor, boolean hasMore)
```
