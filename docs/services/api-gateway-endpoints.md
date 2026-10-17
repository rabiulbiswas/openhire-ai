# api-gateway — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](api-gateway.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **8**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## BffAccountController

Source: [BffAccountController](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java).

### GET `/api/v1/me`

- Handler: `profile` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java#L41)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<MeProfile> profile(Mono<Principal> principal, WebSession session)
```

### PATCH `/api/v1/me`

- Handler: `updateProfile` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java#L46)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<Void> updateProfile(@RequestBody MeProfile profile, WebSession session)
```

### GET `/api/v1/me/sessions`

- Handler: `browserSessions` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java#L62)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<List<SessionInfo>> browserSessions( Mono<Principal> principal, WebSession current, @RequestHeader(name = "User-Agent", defaultValue = "Browser") String userAgent)
```

### DELETE `/api/v1/me/sessions`

- Handler: `signOutOtherSessions` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java#L78)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<Void> signOutOtherSessions(Mono<Principal> principal, WebSession current)
```

## BffSessionController

Source: [BffSessionController](../../api-gateway/src/main/java/com/openhire/gateway/web/BffSessionController.java).

### GET `/bff/session`

- Handler: `session` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffSessionController.java#L56)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<Map<String, Object>> session(Mono<Principal> principal, WebSession session)
```

### GET `/bff/csrf`

- Handler: `csrf` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffSessionController.java#L66)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<Map<String, String>> csrf( @org.springframework.web.bind.annotation.RequestAttribute( "org.springframework.security.web.server.csrf.CsrfToken") Mono<CsrfToken> token)
```

### DELETE `/bff/session`

- Handler: `logout` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffSessionController.java#L74)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<Void> logout(WebSession session)
```

### GET `/bff/logout`

- Handler: `browserLogout` ([source](../../api-gateway/src/main/java/com/openhire/gateway/web/BffSessionController.java#L84)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Mono<ResponseEntity<Void>> browserLogout(Mono<Principal> principal, WebSession session)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### MeProfile

[Definition](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java)

```java
record MeProfile(String name, String title, String timezone, String language)
```

### SessionInfo

[Definition](../../api-gateway/src/main/java/com/openhire/gateway/web/BffAccountController.java)

```java
record SessionInfo(String id, String device, String location, Instant lastActiveAt, boolean current)
```
