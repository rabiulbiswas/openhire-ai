# notification-dispatcher — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](notification-dispatcher.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **3**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## DeliveryController

Source: [DeliveryController](../../notification-dispatcher/src/main/java/com/openhire/dispatch/controller/DeliveryController.java).

### GET `/api/v1/deliveries`

- Handler: `list` ([source](../../notification-dispatcher/src/main/java/com/openhire/dispatch/controller/DeliveryController.java#L29)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `dispatch.list`.

Request bindings and declared response:

```java
Map<String, List<DeliveryView>> list( Authentication auth, @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit)
```

### GET `/api/v1/deliveries/{messageId}`

- Handler: `get` ([source](../../notification-dispatcher/src/main/java/com/openhire/dispatch/controller/DeliveryController.java#L46)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `dispatch.get`.

Request bindings and declared response:

```java
DeliveryView get(Authentication auth, @PathVariable String messageId)
```

### POST `/api/v1/deliveries/{messageId}/retry`

- Handler: `retry` ([source](../../notification-dispatcher/src/main/java/com/openhire/dispatch/controller/DeliveryController.java#L52)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.requireStaff(auth)`; `access.tenant(auth)`.
- Delegated operations: `dispatch.retry`.

Request bindings and declared response:

```java
DeliveryView retry(Authentication auth, @PathVariable String messageId)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### DeliveryView

[Definition](../../notification-dispatcher/src/main/java/com/openhire/dispatch/service/DispatchService.java)

```java
record DeliveryView(
            String messageId,
            String tenantId,
            String audience,
            String requestedChannel,
            String channelUsed,
            String status,
            String reason,
            int attempts,
            String dueAt,
            String createdAt,
            String updatedAt)
```
