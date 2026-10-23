# candidate-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](candidate-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **8**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## CandidateController

Source: [CandidateController](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java).

### POST `/api/v1/internal/candidates/resolve`

- Handler: `resolve` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L22)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.
- Delegated operations: `service.resolve`.

Request bindings and declared response:

```java
public Document resolve(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody Document input)
```

### POST `/api/v1/internal/candidates/project`

- Handler: `project` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L28)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.
- Delegated operations: `service.project`.

Request bindings and declared response:

```java
public Document project(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody Document input)
```

### GET `/api/v1/internal/candidates/{id}/canonical`

- Handler: `canonical` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L34)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.
- Delegated operations: `service.canonical`.

Request bindings and declared response:

```java
public Document canonical( @RequestHeader("X-Internal-Service-Secret") String secret, @PathVariable String id, @RequestParam String tenantId)
```

### GET `/api/v1/candidates/{id}/360`

- Handler: `get` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L43)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.view`.

Request bindings and declared response:

```java
public Document get(Authentication auth, @PathVariable String id)
```

### PUT `/api/v1/candidates/{id}/owner`

- Handler: `owner` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L49)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.owner`.

Request bindings and declared response:

```java
public Map<String, String> owner(Authentication auth, @PathVariable String id, @RequestBody Document body)
```

### GET `/api/v1/merge-suggestions`

- Handler: `suggestions` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L56)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.suggestions`.

Request bindings and declared response:

```java
public Object suggestions(Authentication auth)
```

### POST `/api/v1/merge-suggestions/{id}/dismiss`

- Handler: `dismiss` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L62)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `service.dismiss`.

Request bindings and declared response:

```java
public Object dismiss(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/candidates/merge`

- Handler: `merge` ([source](../../candidate-service/src/main/java/com/openhire/candidateservice/controller/CandidateController.java#L69)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `service.merge`.

Request bindings and declared response:

```java
public Document merge(Authentication auth, @RequestBody Document body)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

No matching record DTOs; follow the handler/source links for class- or map-based payloads.
