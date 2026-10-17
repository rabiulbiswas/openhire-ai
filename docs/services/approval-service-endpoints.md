# approval-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](approval-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **13**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## ApprovalController

Source: [ApprovalController](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java).

### POST `/api/v1/approvals`

- Handler: `create` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L37)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.create`.

Request bindings and declared response:

```java
Object create( @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody CreateApprovalRequest request)
```

### POST `/api/v1/internal/approvals`

- Handler: `createInternal` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L46)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.create`.

Request bindings and declared response:

```java
Object createInternal( @RequestHeader("X-Internal-Service-Secret") String secret, @RequestHeader("X-Tenant-Id") String tenantId, @RequestHeader(name = "X-Actor-Id", defaultValue = "system") String actor, @Valid @RequestBody CreateApprovalRequest request)
```

### GET `/api/v1/approvals`

- Handler: `list` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L57)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
Object list( @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor, @RequestParam(required = false) String scope, @RequestParam(required = false) String assignee, @RequestParam(required = false) String status)
```

### GET `/api/v1/approvals/counts`

- Handler: `counts` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L68)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.counts`.

Request bindings and declared response:

```java
Object counts(@RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### GET `/api/v1/approvals/sla-overdue`

- Handler: `overdue` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L74)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")`.
- Delegated operations: `service.overdue`.

Request bindings and declared response:

```java
Object overdue()
```

### GET `/api/v1/approvals/{id}`

- Handler: `get` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L80)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.get`.

Request bindings and declared response:

```java
Object get(@PathVariable String id)
```

### POST `/api/v1/approvals/{id}/approve`

- Handler: `approve` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L86)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.approve`.

Request bindings and declared response:

```java
void approve( @PathVariable String id, @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/approvals/{id}/reject`

- Handler: `reject` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L94)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.reject`.

Request bindings and declared response:

```java
void reject( @PathVariable String id, @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody ReasonRequest request)
```

### POST `/api/v1/approvals/{id}/request-changes`

- Handler: `requestChanges` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L104)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.requestChanges`.

Request bindings and declared response:

```java
void requestChanges( @PathVariable String id, @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody ReasonRequest request)
```

### POST `/api/v1/approvals/{id}/undo`

- Handler: `undo` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L114)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.undo`.

Request bindings and declared response:

```java
void undo(@PathVariable String id)
```

### POST `/api/v1/approval-delegations`

- Handler: `delegate` ([source](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalController.java#L121)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.
- Delegated operations: `service.delegate`.

Request bindings and declared response:

```java
void delegate( @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody DelegationRequest request)
```

## DelegationPeriodController

Source: [DelegationPeriodController](../../approval-service/src/main/java/com/openhire/approval/api/DelegationPeriodController.java).

### GET `/api/v1/approval-delegation-periods`

- Handler: `list` ([source](../../approval-service/src/main/java/com/openhire/approval/api/DelegationPeriodController.java#L30)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.

Request bindings and declared response:

```java
List<DelegationPeriod> list(@RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/approval-delegation-periods`

- Handler: `create` ([source](../../approval-service/src/main/java/com/openhire/approval/api/DelegationPeriodController.java#L36)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")`.

Request bindings and declared response:

```java
DelegationPeriod create( @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody Request request)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### CreateApprovalRequest

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record CreateApprovalRequest(
            @NotBlank String type,
            @NotBlank String subjectId,
            @NotBlank String title,
            @NotEmpty List<@Valid StepInput> steps,
            List<@Valid FieldChangeInput> diff,
            ImpactInput impact,
            List<@Valid TermInput> terms)
```

### StepInput

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record StepInput(
            @NotBlank String id, @NotBlank String approver, @NotBlank String role, Integer group, Integer slaHours)
```

### FieldChangeInput

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record FieldChangeInput(@NotBlank String field, @NotBlank String before, @NotBlank String after)
```

### ImpactInput

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record ImpactInput(int rescoredApplicants)
```

### TermInput

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record TermInput(@NotBlank String label, @NotBlank String value)
```

### ReasonRequest

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record ReasonRequest(@NotBlank String reason)
```

### DelegationRequest

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/ApprovalDtos.java)

```java
record DelegationRequest(@NotBlank String id, @NotBlank String toUser, Instant until)
```

### Request

[Definition](../../approval-service/src/main/java/com/openhire/approval/api/DelegationPeriodController.java)

```java
record Request(@NotBlank String delegateId, @NotNull Instant startsAt, @NotNull Instant endsAt)
```

### DelegationPeriod

[Definition](../../approval-service/src/main/java/com/openhire/approval/domain/DelegationPeriod.java)

```java
record DelegationPeriod(
        @Id String id,
        String tenantId,
        String ownerId,
        String delegateId,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt)
```
