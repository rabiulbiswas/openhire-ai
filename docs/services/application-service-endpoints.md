# application-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](application-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **35**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## IntakeController

Source: [IntakeController](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java).

### POST `/api/v1/intake/applications`

- Handler: `submit` ([source](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java#L38)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
ResponseEntity<ApplicationView> submit( Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestBody IntakeCommand command)
```

### GET `/api/v1/intake/applications/{id}`

- Handler: `get` ([source](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java#L50)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `service.get`.

Request bindings and declared response:

```java
ResponseEntity<ApplicationView> get(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/intake/applications`

- Handler: `list` ([source](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java#L56)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
Page list( Authentication auth, @RequestParam(required = false) String jobId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size)
```

### PATCH `/api/v1/intake/applications/{id}/stage`

- Handler: `transition` ([source](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java#L70)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `service.transition`.

Request bindings and declared response:

```java
ResponseEntity<ApplicationView> transition( Authentication auth, @PathVariable String id, @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody StageChange request)
```

## ApiIntakeController

Source: [ApiIntakeController](../../application-service/src/main/java/com/openhire/applicationservice/workflow/ApiIntakeController.java).

### POST `/api/v1/public/api-applications`

- Handler: `json` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/ApiIntakeController.java#L56)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
public Object json( @RequestHeader("X-API-Key") String apiKey, @RequestHeader("Idempotency-Key") String key, @RequestBody Document input)
```

### POST `/api/v1/public/api-applications`

- Handler: `multipart` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/ApiIntakeController.java#L70)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
public Object multipart( @RequestHeader("X-API-Key") String apiKey, @RequestHeader("Idempotency-Key") String key, @RequestParam Map<String, String> fields, @RequestPart MultipartFile resume)
```

## BulkImportController

Source: [BulkImportController](../../application-service/src/main/java/com/openhire/applicationservice/workflow/BulkImportController.java).

### POST `/api/v1/imports`

- Handler: `start` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/BulkImportController.java#L39)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.consentVersion`, `service.store`, `service.job`.

Request bindings and declared response:

```java
public Object start( Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestParam String jobId, @RequestParam boolean processingConsent, @RequestParam String consentVersion, @RequestPart MultipartFile file)
```

### GET `/api/v1/imports/{id}`

- Handler: `get` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/BulkImportController.java#L148)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.

Request bindings and declared response:

```java
public Object get(Authentication auth, @PathVariable String id)
```

## PrivacyLookupController

Source: [PrivacyLookupController](../../application-service/src/main/java/com/openhire/applicationservice/workflow/PrivacyLookupController.java).

### GET `/api/v1/internal/candidates/{candidateId}/application-ids`

- Handler: `applicationIds` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/PrivacyLookupController.java#L25)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
Map<String, List<String>> applicationIds( @RequestHeader("X-Internal-Service-Secret") String secret, @PathVariable String candidateId, @RequestParam String tenantId)
```

## SubmissionController

Source: [SubmissionController](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java).

### GET `/api/v1/internal/applications/{id}/projection`

- Handler: `projection` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L26)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.
- Delegated operations: `service.get`.

Request bindings and declared response:

```java
public Document projection( @RequestHeader("X-Internal-Service-Secret") String secret, @PathVariable String id, @RequestParam String tenantId)
```

### POST `/api/v1/internal/applications/{id}/receipt-check`

- Handler: `receiptCheck` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L53)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
public Document receiptCheck( @RequestHeader("X-Internal-Service-Secret") String secret, @PathVariable String id, @RequestBody Document body)
```

### GET `/api/v1/applications/{id}/decision`

- Handler: `decision` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L65)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.tenant(auth)`.
- Delegated operations: `service.decision`.

Request bindings and declared response:

```java
public Document decision(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/applications/{id}/decision`

- Handler: `decide` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L71)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.decide`, `service.decision`.

Request bindings and declared response:

```java
public Document decide(Authentication auth, @PathVariable String id, @RequestBody Document body)
```

### GET `/api/v1/public/consent`

- Handler: `consent` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L99)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.consent`.

Request bindings and declared response:

```java
public Object consent(@RequestParam(required = false) String tenantId)
```

### POST `/api/v1/applications`

- Handler: `submit` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L105)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
public Object submit( Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestParam Map<String, String> fields, @RequestPart(required = false) MultipartFile resume)
```

### POST `/api/v1/applications`

- Handler: `json` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L121)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
public Object json(Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestBody Document input)
```

### POST `/api/v1/public/applications`

- Handler: `career` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L166)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
public Object career( @RequestHeader("Idempotency-Key") String key, @RequestParam Map<String, String> fields, @RequestPart MultipartFile resume)
```

### GET `/api/v1/public/applications/{id}`

- Handler: `status` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L196)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
public Object status( @PathVariable String id, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String token)
```

### POST `/api/v1/public/applications/{id}/withdraw`

- Handler: `withdraw` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L213)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.withdraw`.

Request bindings and declared response:

```java
public Object withdraw( @PathVariable String id, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String token)
```

### GET `/api/v1/applications`

- Handler: `list` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L223)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
public Object list( Authentication auth, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size)
```

### GET `/api/v1/jobs/{id}/applications`

- Handler: `byJob` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L257)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
public Object byJob( Authentication auth, @PathVariable String id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size)
```

### GET `/api/v1/jobs/{id}/pipeline`

- Handler: `pipeline` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L271)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.list`, `service.job`.

Request bindings and declared response:

```java
public Object pipeline(Authentication auth, @PathVariable String id, @RequestParam(defaultValue = "0") int page)
```

### GET `/api/v1/applications/{id}/assessment`

- Handler: `assessment` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L299)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.get`.

Request bindings and declared response:

```java
public Object assessment(Authentication auth, @PathVariable String id)
```

### PATCH `/api/v1/applications/{id}/stage`

- Handler: `stage` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L307)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.card`, `service.move`.

Request bindings and declared response:

```java
public Object stage(Authentication auth, @PathVariable String id, @RequestBody Document body)
```

### POST `/api/v1/applications/{id}/shortlist`

- Handler: `shortlist` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L319)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.card`, `service.move`.

Request bindings and declared response:

```java
public Object shortlist(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/applications/{id}/reject`

- Handler: `reject` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L326)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.card`, `service.move`.

Request bindings and declared response:

```java
public Object reject(Authentication auth, @PathVariable String id, @RequestBody Document body)
```

### POST `/api/v1/applications/{id}/undo-decision`

- Handler: `undo` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L346)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.undo`.

Request bindings and declared response:

```java
public Object undo(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/applications/{id}/retry`

- Handler: `retry` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L353)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.retry`.

Request bindings and declared response:

```java
public Object retry(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/applications/{id}/eligibility`

- Handler: `eligibility` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L360)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.reviewEligibility`.

Request bindings and declared response:

```java
public Object eligibility(Authentication auth, @PathVariable String id, @RequestBody Document body)
```

### POST `/api/v1/applications/{id}/resume`

- Handler: `replace` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L367)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.replaceResume`.

Request bindings and declared response:

```java
public Object replace(Authentication auth, @PathVariable String id, @RequestPart MultipartFile resume)
```

### POST `/api/v1/assessments/{id}/override`

- Handler: `override` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L375)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.overrideScore`.

Request bindings and declared response:

```java
public Object override(Authentication auth, @PathVariable String id, @RequestBody Document input)
```

### PUT `/api/v1/partner-assignments/{actorId}/{jobId}`

- Handler: `assignment` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L387)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.job`, `service.store`.

Request bindings and declared response:

```java
public Object assignment( Authentication auth, @PathVariable String actorId, @PathVariable String jobId, @RequestBody Document input)
```

### GET `/api/v1/partner/jobs`

- Handler: `assigned` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L408)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.tenant(auth)`; `access.actor(auth)`; `access.role(auth, "AGENCY_RECRUITER")`; `access.staff(auth)`.
- Delegated operations: `service.store`, `service.job`.

Request bindings and declared response:

```java
public Object assigned(Authentication auth)
```

### GET `/api/v1/partner/submissions`

- Handler: `mySubmissions` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L434)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.staff(auth)`; `access.role(auth, "AGENCY_RECRUITER")`; `access.role(auth, "EMPLOYEE")`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
public Object mySubmissions(Authentication auth)
```

### GET `/api/v1/me/referrals`

- Handler: `mySubmissions` ([source](../../application-service/src/main/java/com/openhire/applicationservice/workflow/SubmissionController.java#L434)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.staff(auth)`; `access.role(auth, "AGENCY_RECRUITER")`; `access.role(auth, "EMPLOYEE")`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
public Object mySubmissions(Authentication auth)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### StageChange

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java)

```java
record StageChange(@NotNull ApplicationStage stage, String reason)
```

### Page

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java)

```java
record Page(List<ApplicationView> data, int page, int size)
```

### ApplicationView

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/adapter/in/web/IntakeController.java)

```java
record ApplicationView(
            String id,
            String jobId,
            String jobTitle,
            int jobVersion,
            String name,
            String email,
            String owner,
            String source,
            String externalReference,
            boolean processingConsent,
            String consentVersion,
            String consentRecordedBy,
            ApplicationStage stage,
            Instant submittedAt,
            Instant updatedAt,
            Long revision,
            List<ApplicationRecord.Decision> decisions)
```

### ApplicationRecord

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/domain/ApplicationRecord.java)

```java
record ApplicationRecord(
        String id,
        String tenantId,
        String submittedBy,
        String requestFingerprint,
        IntakeCommand intake,
        String jobTitle,
        int jobVersion,
        ApplicationStage stage,
        Instant submittedAt,
        Instant updatedAt,
        Long revision,
        List<Decision> decisions,
        List<ApplicationEvent> pendingEvents)
```

### Decision

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/domain/ApplicationRecord.java)

```java
record Decision(ApplicationStage from, ApplicationStage to, String actorId, String reason, Instant at)
```

### IntakeCommand

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/domain/IntakeCommand.java)

```java
record IntakeCommand(
        String jobId,
        String name,
        String email,
        String owner,
        boolean processingConsent,
        String consentVersion,
        String externalReference)
```

### ApplicationEvent

[Definition](../../application-service/src/main/java/com/openhire/applicationservice/domain/ApplicationEvent.java)

```java
record ApplicationEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String correlationId,
        Map<String, Object> payload)
```
