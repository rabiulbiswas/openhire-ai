# communication-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](communication-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **19**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## ConversationController

Source: [ConversationController](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java).

### GET `/api/v1/conversations`

- Handler: `list` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java#L35)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.conversations`.

Request bindings and declared response:

```java
Page<ConversationSummaryView> list(Authentication auth)
```

### POST `/api/v1/conversations`

- Handler: `start` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java#L41)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.startConversation`.

Request bindings and declared response:

```java
ConversationView start(Authentication auth, @RequestBody StartInput input)
```

### GET `/api/v1/conversations/{id}`

- Handler: `get` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java#L50)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.conversation`.

Request bindings and declared response:

```java
ConversationView get(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/conversations/{id}/messages`

- Handler: `send` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java#L56)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.send`.

Request bindings and declared response:

```java
ChatMessageView send(Authentication auth, @PathVariable String id, @RequestBody SendInput input)
```

### POST `/api/v1/conversations/{id}/ai-draft`

- Handler: `aiDraft` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java#L71)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.aiDraft`.

Request bindings and declared response:

```java
Map<String, String> aiDraft(Authentication auth, @PathVariable String id)
```

## InternalMessageController

Source: [InternalMessageController](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/InternalMessageController.java).

### POST `/api/v1/internal/messages/template`

- Handler: `template` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/InternalMessageController.java#L28)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `access.internal(secret)`.
- Delegated operations: `communication.sendTemplate`.

Request bindings and declared response:

```java
TemplateSend template(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody TemplateInput input)
```

## NotificationController

Source: [NotificationController](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java).

### GET `/api/v1/notifications`

- Handler: `list` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java#L41)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `communication.notifications`.

Request bindings and declared response:

```java
Page<NotificationView> list( Authentication auth, @RequestParam(defaultValue = "all") String filter, @RequestParam(defaultValue = "50") int limit)
```

### GET `/api/v1/notifications/unread-count`

- Handler: `unread` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java#L49)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `communication.unreadCount`.

Request bindings and declared response:

```java
Map<String, Long> unread(Authentication auth)
```

### PATCH `/api/v1/notifications/{id}`

- Handler: `read` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java#L54)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `communication.markRead`.

Request bindings and declared response:

```java
Map<String, String> read(Authentication auth, @PathVariable String id, @RequestBody ReadInput input)
```

### POST `/api/v1/notifications/read-all`

- Handler: `readAll` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java#L61)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `communication.markAllRead`.

Request bindings and declared response:

```java
Map<String, String> readAll(Authentication auth)
```

### GET `/api/v1/me/notification-preferences`

- Handler: `preferences` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java#L67)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `communication.preferences`.

Request bindings and declared response:

```java
PreferencesView preferences(Authentication auth)
```

### PUT `/api/v1/me/notification-preferences`

- Handler: `update` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java#L72)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `communication.updatePreferences`.

Request bindings and declared response:

```java
PreferencesView update(Authentication auth, @RequestBody PreferencesInput input)
```

## PublicCandidateController

Source: [PublicCandidateController](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java).

### GET `/api/v1/public/applications/{applicationId}/messages`

- Handler: `thread` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java#L37)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `communication.candidateThread`.

Request bindings and declared response:

```java
PortalThreadView thread( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

### POST `/api/v1/public/applications/{applicationId}/messages`

- Handler: `reply` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java#L45)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `communication.candidateReply`.

Request bindings and declared response:

```java
ChatMessageView reply( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt, @RequestBody ReplyInput input)
```

### GET `/api/v1/public/applications/{applicationId}/communication-preferences`

- Handler: `preferences` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java#L55)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `communication.candidatePreferences`.

Request bindings and declared response:

```java
CandidatePreferencesView preferences( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

### PUT `/api/v1/public/applications/{applicationId}/communication-preferences`

- Handler: `update` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java#L63)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `communication.updateCandidatePreferences`.

Request bindings and declared response:

```java
CandidatePreferencesView update( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt, @RequestBody PreferencesInput input)
```

## TemplateController

Source: [TemplateController](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/TemplateController.java).

### GET `/api/v1/message-templates`

- Handler: `list` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/TemplateController.java#L32)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.templates`.

Request bindings and declared response:

```java
Page<TemplateView> list(Authentication auth)
```

### PUT `/api/v1/message-templates/{key}`

- Handler: `update` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/TemplateController.java#L38)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.updateTemplate`.

Request bindings and declared response:

```java
TemplateView update(Authentication auth, @PathVariable String key, @RequestBody TemplateInput input)
```

### POST `/api/v1/message-templates/{key}/preview`

- Handler: `preview` ([source](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/TemplateController.java#L55)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.recruiter(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `communication.preview`.

Request bindings and declared response:

```java
PreviewView preview( Authentication auth, @PathVariable String key, @RequestBody(required = false) Map<String, String> sample)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### StartInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java)

```java
record StartInput(String applicationId)
```

### SendInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/ConversationController.java)

```java
record SendInput(String text, String channel)
```

### TemplateInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/InternalMessageController.java)

```java
record TemplateInput(
            String tenantId, String applicationId, String templateKey, Map<String, String> variables, String actor)
```

### ReadInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java)

```java
record ReadInput(Boolean read)
```

### PreferencesInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java)

```java
record PreferencesInput(Map<String, Map<String, Boolean>> matrix, QuietInput quietHours, String emailDigest)
```

### ReplyInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java)

```java
record ReplyInput(String text)
```

### QuietInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java)

```java
record QuietInput(boolean enabled, String from, String to)
```

### PreferencesInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/PublicCandidateController.java)

```java
record PreferencesInput(String preferredChannel, QuietInput quietHours, String timezone, boolean marketingOptOut)
```

### TemplateInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/TemplateController.java)

```java
record TemplateInput(List<String> channels, String subject, String body)
```

### TemplateSend

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationService.java)

```java
record TemplateSend(boolean sent, String reason)
```

### Page

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record Page<T>(List<T> data, String nextCursor, boolean hasMore)
```

### ConversationSummaryView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record ConversationSummaryView(
            String id,
            String candidateId,
            String candidateName,
            String channel,
            String lastMessage,
            String lastMessageAt,
            boolean unread)
```

### ChatMessageView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record ChatMessageView(
            String id, String from, String text, String at, String status, String statusReason, String channel)
```

### ConversationView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record ConversationView(
            String id,
            String candidateId,
            String candidateName,
            String applicationId,
            String channel,
            List<ChatMessageView> messages,
            String aiDraftReply)
```

### PortalThreadView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record PortalThreadView(String candidateName, List<ChatMessageView> messages)
```

### TemplateView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record TemplateView(
            String id,
            String key,
            String name,
            List<String> channels,
            String subject,
            String body,
            int version,
            String updatedAt,
            List<String> variables)
```

### PreviewView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record PreviewView(String subject, String body)
```

### NotificationView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record NotificationView(
            String id,
            String type,
            String title,
            String body,
            LinkView link,
            String createdAt,
            String readAt,
            boolean mention)
```

### PreferencesView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record PreferencesView(
            Map<String, Map<String, Boolean>> matrix, QuietHoursView quietHours, String emailDigest)
```

### CandidatePreferencesView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record CandidatePreferencesView(
            String preferredChannel, QuietHoursView quietHours, String timezone, boolean marketingOptOut)
```

### QuietInput

[Definition](../../communication-service/src/main/java/com/openhire/communication/adapter/in/web/NotificationController.java)

```java
record QuietInput(boolean enabled, String from, String to)
```

### LinkView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record LinkView(String route, Map<String, String> params)
```

### QuietHoursView

[Definition](../../communication-service/src/main/java/com/openhire/communication/application/CommunicationViews.java)

```java
record QuietHoursView(boolean enabled, String from, String to)
```
