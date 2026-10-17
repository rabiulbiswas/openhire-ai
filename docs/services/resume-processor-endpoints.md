# resume-processor — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](resume-processor.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **4**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## ResumeController

Source: [ResumeController](../../resume-processor/src/main/java/com/openhire/resumeprocessor/ResumeController.java).

### POST `/api/v1/internal/resumes`

- Handler: `upload` ([source](../../resume-processor/src/main/java/com/openhire/resumeprocessor/ResumeController.java#L21)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
public Document upload( @RequestHeader("X-Internal-Service-Secret") String secret, @RequestParam String tenantId, @RequestParam String applicationId, @RequestPart MultipartFile file)
```

### GET `/api/v1/internal/resumes/{id}`

- Handler: `result` ([source](../../resume-processor/src/main/java/com/openhire/resumeprocessor/ResumeController.java#L32)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
public Document result( @RequestHeader("X-Internal-Service-Secret") String secret, @RequestParam String tenantId, @PathVariable String id)
```

### POST `/api/v1/internal/resumes/{id}/retry`

- Handler: `retry` ([source](../../resume-processor/src/main/java/com/openhire/resumeprocessor/ResumeController.java#L41)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.

Request bindings and declared response:

```java
public Document retry( @RequestHeader("X-Internal-Service-Secret") String secret, @RequestParam String tenantId, @PathVariable String id)
```

### GET `/api/v1/resumes/{id}/download`

- Handler: `download` ([source](../../resume-processor/src/main/java/com/openhire/resumeprocessor/ResumeController.java#L51)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.

Request bindings and declared response:

```java
public ResponseEntity<byte[]> download(Authentication auth, @PathVariable String id)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

No matching record DTOs; follow the handler/source links for class- or map-based payloads.
