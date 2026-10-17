# ai-workers — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](ai-workers.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **2**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## IntegrityController

Source: [IntegrityController](../../ai-workers/src/main/java/com/openhire/aiworkers/integrity/adapter/in/web/IntegrityController.java).

### GET `/api/v1/integrity/signals`

- Handler: `list` ([source](../../ai-workers/src/main/java/com/openhire/aiworkers/integrity/adapter/in/web/IntegrityController.java#L31)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `integrity.signals`.

Request bindings and declared response:

```java
List<Map<String, Object>> list(Authentication auth, @RequestParam String applicationId)
```

### POST `/api/v1/integrity/signals/{id}/dismiss`

- Handler: `dismiss` ([source](../../ai-workers/src/main/java/com/openhire/aiworkers/integrity/adapter/in/web/IntegrityController.java#L39)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `integrity.dismiss`.

Request bindings and declared response:

```java
Map<String, Object> dismiss(Authentication auth, @PathVariable String id, @RequestBody DismissInput body)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### DismissInput

[Definition](../../ai-workers/src/main/java/com/openhire/aiworkers/integrity/adapter/in/web/IntegrityController.java)

```java
record DismissInput(String reason)
```
