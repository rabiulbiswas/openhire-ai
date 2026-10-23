# integration-hub — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](integration-hub.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **23**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## CalendarController

Source: [CalendarController](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java).

### POST `/api/v1/connectors/{id}/authorize`

- Handler: `authorize` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java#L28)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `calendars.authorize`.

Request bindings and declared response:

```java
Map<String, String> authorize(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/connectors/oauth/callback`

- Handler: `callback` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java#L35)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `calendars.complete`.

Request bindings and declared response:

```java
Map<String, String> callback(Authentication auth, @RequestBody Callback callback)
```

### GET `/api/v1/connectors/{id}/connection`

- Handler: `status` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java#L41)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `calendars.status`.

Request bindings and declared response:

```java
Map<String, String> status(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/connectors/{id}/calendar/events`

- Handler: `events` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java#L46)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `calendars.list`.

Request bindings and declared response:

```java
Map<String, Object> events(Authentication auth, @PathVariable String id)
```

### DELETE `/api/v1/connectors/{id}/connection`

- Handler: `disconnect` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java#L51)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `calendars.disconnect`.

Request bindings and declared response:

```java
Map<String, String> disconnect(Authentication auth, @PathVariable String id)
```

## CalendarSyncController

Source: [CalendarSyncController](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarSyncController.java).

### GET `/api/v1/connectors/interview-sync`

- Handler: `get` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarSyncController.java#L43)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Document get(Authentication auth)
```

### PUT `/api/v1/connectors/interview-sync`

- Handler: `save` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarSyncController.java#L51)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`; `access.actor(auth)`.

Request bindings and declared response:

```java
Document save(Authentication auth, @RequestBody Input input)
```

## CalendarWorkController

Source: [CalendarWorkController](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarWorkController.java).

### PUT `/api/v1/connectors/{id}/calendar/work/{resource}`

- Handler: `save` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarWorkController.java#L35)).
- Success status: \@ResponseStatus(HttpStatus.ACCEPTED).
- Controller access/tenant calls: `tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `queue.enqueue`.

Request bindings and declared response:

```java
Document save( Authentication auth, @PathVariable String id, @PathVariable String resource, @RequestHeader("Idempotency-Key") String key, @RequestBody Input input)
```

### GET `/api/v1/connectors/{id}/calendar/work/{resource}`

- Handler: `status` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarWorkController.java#L52)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `queue.get`.

Request bindings and declared response:

```java
Document status(Authentication auth, @PathVariable String id, @PathVariable String resource)
```

## IntegrationController

Source: [IntegrationController](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java).

### GET `/api/v1/connectors/catalog`

- Handler: `catalog` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L63)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
List<Map<String, Object>> catalog(Authentication auth)
```

### GET `/api/v1/connectors`

- Handler: `connectors` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L87)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `service.page`.

Request bindings and declared response:

```java
List<View> connectors( Authentication auth, @RequestParam(required = false) String after, @RequestParam(defaultValue = "200") int limit)
```

### GET `/api/v1/webhooks`

- Handler: `webhooks` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L97)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `service.page`.

Request bindings and declared response:

```java
List<View> webhooks( Authentication auth, @RequestParam(required = false) String after, @RequestParam(defaultValue = "200") int limit)
```

### POST `/api/v1/connectors`

- Handler: `createConnector` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L107)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
View createConnector(Authentication auth, @RequestBody Input input)
```

### POST `/api/v1/webhooks`

- Handler: `createWebhook` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L113)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
View createWebhook(Authentication auth, @RequestBody Input input)
```

### PUT `/api/v1/connectors/{id}`

- Handler: `updateConnector` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L119)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
View updateConnector(Authentication auth, @PathVariable String id, @RequestBody Input input)
```

### PUT `/api/v1/webhooks/{id}`

- Handler: `updateWebhook` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java#L124)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
View updateWebhook(Authentication auth, @PathVariable String id, @RequestBody Input input)
```

## McpController

Source: [McpController](../../integration-hub/src/main/java/com/openhire/integration/controller/McpController.java).

### POST `/api/v1/mcp`

- Handler: `call` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/McpController.java#L44)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `policy.require(auth, "integrations.read")`; `policy.require(auth, "integrations.write")`; `access.actor(auth)`.
- Delegated operations: `service.page`, `confirmations.prepare`, `confirmations.execute`.

Request bindings and declared response:

```java
ResponseEntity<?> call( Authentication auth, @RequestHeader(value = "Origin", required = false) String origin, @RequestBody Map<String, Object> request)
```

### GET `/api/v1/connectors/mcp-confirmations`

- Handler: `pending` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/McpController.java#L198)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `policy.browser(auth)`; `access.actor(auth)`.
- Delegated operations: `confirmations.pending`.

Request bindings and declared response:

```java
List<org.bson.Document> pending(Authentication auth)
```

### POST `/api/v1/connectors/mcp-confirmations/{id}/decision`

- Handler: `decide` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/McpController.java#L205)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `policy.browser(auth)`; `access.actor(auth)`.
- Delegated operations: `confirmations.decide`.

Request bindings and declared response:

```java
Map<String, String> decide(Authentication auth, @PathVariable String id, @RequestBody Decision decision)
```

## McpResourceMetadata

Source: [McpResourceMetadata](../../integration-hub/src/main/java/com/openhire/integration/controller/McpResourceMetadata.java).

### GET `/api/v1/public/mcp/resource-metadata`

- Handler: `metadata` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/McpResourceMetadata.java#L21)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Map<String, Object> metadata()
```

## WebhookDeliveryController

Source: [WebhookDeliveryController](../../integration-hub/src/main/java/com/openhire/integration/controller/WebhookDeliveryController.java).

### GET `/api/v1/webhooks/delivery-status`

- Handler: `status` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/WebhookDeliveryController.java#L34)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `queue.enabled`.

Request bindings and declared response:

```java
Map<String, Boolean> status(Authentication auth)
```

### GET `/api/v1/webhooks/{id}/deliveries`

- Handler: `history` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/WebhookDeliveryController.java#L40)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `queue.history`.

Request bindings and declared response:

```java
List<Document> history(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/webhooks/{id}/deliveries/{delivery}/replay`

- Handler: `replay` ([source](../../integration-hub/src/main/java/com/openhire/integration/controller/WebhookDeliveryController.java#L45)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`; `access.actor(auth)`.
- Delegated operations: `queue.replay`.

Request bindings and declared response:

```java
Map<String, String> replay(Authentication auth, @PathVariable String id, @PathVariable String delivery)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### Callback

[Definition](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarController.java)

```java
record Callback(String state, String code)
```

### Input

[Definition](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarSyncController.java)

```java
record Input(String connectorId, boolean enabled)
```

### Input

[Definition](../../integration-hub/src/main/java/com/openhire/integration/controller/CalendarWorkController.java)

```java
record Input(Long version, CalendarEvent event, boolean cancel)
```

### Input

[Definition](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java)

```java
record Input(
            String name,
            String provider,
            String endpoint,
            String secretRef,
            List<String> eventTypes,
            boolean enabled,
            Long version)
```

### View

[Definition](../../integration-hub/src/main/java/com/openhire/integration/controller/IntegrationController.java)

```java
record View(
            String id,
            String name,
            String provider,
            String endpoint,
            List<String> eventTypes,
            boolean enabled,
            long version,
            String status,
            boolean secretReferenceConfigured)
```

### Decision

[Definition](../../integration-hub/src/main/java/com/openhire/integration/controller/McpController.java)

```java
record Decision(boolean approve)
```

### CalendarEvent

[Definition](../../integration-hub/src/main/java/com/openhire/integration/domain/CalendarEvent.java)

```java
record CalendarEvent(
        String title, Instant start, Instant end, String timezone, List<String> attendees, boolean onlineMeeting)
```
