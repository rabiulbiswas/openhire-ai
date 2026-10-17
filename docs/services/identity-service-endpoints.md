# identity-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](identity-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **44**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## IdentityController

Source: [IdentityController](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java).

### POST `/api/v1/platform/organizations`

- Handler: `createOrganization` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L47)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.createOrganization`.

Request bindings and declared response:

```java
Object createOrganization(@Valid @RequestBody CreateOrganizationRequest request)
```

### GET `/api/v1/platform/organizations`

- Handler: `organizations` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L54)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.organizations`.

Request bindings and declared response:

```java
Object organizations()
```

### PATCH `/api/v1/platform/organizations/{id}/status`

- Handler: `organizationStatus` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L60)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.changeOrganizationStatus`.

Request bindings and declared response:

```java
Object organizationStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request)
```

### GET `/api/v1/users`

- Handler: `users` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L66)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('ORG_ADMIN','RECRUITER')")`.
- Delegated operations: `service.members`.

Request bindings and declared response:

```java
Object users()
```

### PATCH `/api/v1/users/{id}/roles`

- Handler: `updateRoles` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L72)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.updateMemberRoles`.

Request bindings and declared response:

```java
Object updateRoles(@PathVariable String id, @Valid @RequestBody RolesRequest request)
```

### DELETE `/api/v1/users/{id}`

- Handler: `disableUser` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L78)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.disableMember`.

Request bindings and declared response:

```java
Object disableUser(@PathVariable String id)
```

### GET `/api/v1/roles`

- Handler: `roles` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L84)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.roles`.

Request bindings and declared response:

```java
Object roles()
```

### POST `/api/v1/roles`

- Handler: `createRole` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L90)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.createRole`.

Request bindings and declared response:

```java
Object createRole(@Valid @RequestBody CreateRoleRequest request)
```

### PUT `/api/v1/roles/{id}/permissions`

- Handler: `permissions` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L97)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.updatePermissions`.

Request bindings and declared response:

```java
Object permissions(@PathVariable String id, @Valid @RequestBody PermissionsRequest request)
```

### POST `/api/v1/invites`

- Handler: `invite` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L103)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.invite`.

Request bindings and declared response:

```java
Object invite(@Valid @RequestBody InviteRequest request)
```

### GET `/api/v1/invites`

- Handler: `invites` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L111)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.invitations`.

Request bindings and declared response:

```java
Object invites()
```

### POST `/api/v1/invites/{id}/resend`

- Handler: `resend` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L119)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.resendInvitation`.

Request bindings and declared response:

```java
Object resend(@PathVariable String id)
```

### GET `/api/v1/public/invitations/{token}`

- Handler: `invitation` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L126)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.invitation`.

Request bindings and declared response:

```java
Object invitation(@PathVariable String token)
```

### POST `/api/v1/public/invitations/{token}/accept`

- Handler: `accept` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L131)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.acceptInvitation`.

Request bindings and declared response:

```java
Object accept(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest request)
```

### POST `/api/v1/public/verifications/{code}/complete`

- Handler: `completeVerification` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L137)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Object completeVerification(@PathVariable String code)
```

### POST `/api/v1/internal/api-keys/validate`

- Handler: `validateApiKey` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L143)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.validateApiKey`.

Request bindings and declared response:

```java
Object validateApiKey( @RequestHeader("X-Internal-Secret") String suppliedSecret, @Valid @RequestBody ApiKeyValidationRequest request)
```

### GET `/api/v1/api-keys`

- Handler: `apiKeys` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L155)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.apiKeys`.

Request bindings and declared response:

```java
Object apiKeys()
```

### POST `/api/v1/api-keys`

- Handler: `createApiKey` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L161)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.createApiKey`.

Request bindings and declared response:

```java
Object createApiKey(@Valid @RequestBody ApiKeyRequest request)
```

### POST `/api/v1/api-keys/{id}/rotate`

- Handler: `rotateApiKey` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L169)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.rotateApiKey`.

Request bindings and declared response:

```java
Object rotateApiKey(@PathVariable String id)
```

### DELETE `/api/v1/api-keys/{id}`

- Handler: `revokeApiKey` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L176)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.revokeApiKey`.

Request bindings and declared response:

```java
void revokeApiKey(@PathVariable String id)
```

### GET `/api/v1/setup`

- Handler: `setup` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L183)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.setupProgress`.

Request bindings and declared response:

```java
Object setup()
```

### PATCH `/api/v1/setup/{step}/complete`

- Handler: `setupStep` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L189)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.completeSetupStep`.

Request bindings and declared response:

```java
Object setupStep(@PathVariable String step)
```

### GET `/api/v1/auth-policy`

- Handler: `authPolicy` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L195)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.securitySettings`.

Request bindings and declared response:

```java
Object authPolicy()
```

### PUT `/api/v1/auth-policy`

- Handler: `authPolicy` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L201)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.updateAuthPolicy`.

Request bindings and declared response:

```java
Object authPolicy(@Valid @RequestBody AuthPolicy policy)
```

### GET `/api/v1/sso-config`

- Handler: `sso` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L207)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.securitySettings`.

Request bindings and declared response:

```java
Object sso()
```

### PUT `/api/v1/sso-config`

- Handler: `sso` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L213)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('ORG_ADMIN')")`.
- Delegated operations: `service.updateSso`.

Request bindings and declared response:

```java
Object sso(@Valid @RequestBody SsoSettings settings)
```

### GET `/api/v1/platform/organizations/{id}/quota`

- Handler: `quota` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L219)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.securitySettings`.

Request bindings and declared response:

```java
Object quota(@PathVariable String id)
```

### PUT `/api/v1/platform/organizations/{id}/quota`

- Handler: `quota` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L225)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.updateQuota`.

Request bindings and declared response:

```java
Object quota(@PathVariable String id, @Valid @RequestBody TenantQuota quota)
```

### POST `/api/v1/platform/organizations/{id}/break-glass`

- Handler: `breakGlass` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L231)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.requestBreakGlass`.

Request bindings and declared response:

```java
Object breakGlass(@PathVariable String id, @Valid @RequestBody BreakGlassRequest request)
```

### GET `/api/v1/platform/organizations/{id}/break-glass`

- Handler: `breakGlass` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java#L237)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasRole('SUPER_ADMIN')")`.
- Delegated operations: `service.breakGlassRequests`.

Request bindings and declared response:

```java
Object breakGlass(@PathVariable String id)
```

## SamlController

Source: [SamlController](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/SamlController.java).

### GET `/api/v1/sso-config/saml`

- Handler: `get` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/SamlController.java#L67)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Object get(Authentication auth)
```

### PUT `/api/v1/sso-config/saml`

- Handler: `save` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/SamlController.java#L77)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `directory.configureSaml`.

Request bindings and declared response:

```java
Object save(Authentication auth, @RequestBody Input input)
```

### POST `/api/v1/sso-config/saml/links`

- Handler: `link` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/SamlController.java#L134)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `directory.link`.

Request bindings and declared response:

```java
Object link(Authentication auth, @RequestBody Link input)
```

## ScimController

Source: [ScimController](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java).

### GET `/api/v1/scim/v2/ServiceProviderConfig`

- Handler: `config` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L50)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Map<String, Object> config(Authentication auth)
```

### GET `/api/v1/scim/v2/ResourceTypes`

- Handler: `resources` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L78)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Map<String, Object> resources(Authentication auth)
```

### GET `/api/v1/scim/v2/ResourceTypes/User`

- Handler: `userResource` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L142)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Object userResource(Authentication auth)
```

### GET `/api/v1/scim/v2/Schemas`

- Handler: `schemas` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L158)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Object schemas(Authentication auth)
```

### GET `/api/v1/scim/v2/Schemas/{id}`

- Handler: `schema` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L174)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.

Request bindings and declared response:

```java
Object schema(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/scim/v2/Users`

- Handler: `list` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L182)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `users.list`.

Request bindings and declared response:

```java
Object list( Authentication auth, @RequestParam(defaultValue = "1") int startIndex, @RequestParam(defaultValue = "100") int count, @RequestParam(required = false) String filter)
```

### GET `/api/v1/scim/v2/Users/{id}`

- Handler: `get` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L191)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `users.get`.

Request bindings and declared response:

```java
ResponseEntity<?> get(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/scim/v2/Users`

- Handler: `create` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L196)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `users.create`.

Request bindings and declared response:

```java
ResponseEntity<?> create(Authentication auth, @RequestBody Map<String, Object> body)
```

### PUT `/api/v1/scim/v2/Users/{id}`

- Handler: `replace` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L201)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `users.update`.

Request bindings and declared response:

```java
ResponseEntity<?> replace( Authentication auth, @PathVariable String id, @RequestBody Map<String, Object> body, @RequestHeader(value = "If-Match", required = false) String etag)
```

### PATCH `/api/v1/scim/v2/Users/{id}`

- Handler: `patch` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L210)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `users.get`, `users.update`.

Request bindings and declared response:

```java
ResponseEntity<?> patch( Authentication auth, @PathVariable String id, @RequestBody Map<String, Object> body, @RequestHeader(value = "If-Match", required = false) String etag)
```

### DELETE `/api/v1/scim/v2/Users/{id}`

- Handler: `delete` ([source](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/ScimController.java#L255)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `tenant(auth)`.
- Delegated operations: `users.delete`.

Request bindings and declared response:

```java
void delete( Authentication auth, @PathVariable String id, @RequestHeader(value = "If-Match", required = false) String etag)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### CreateOrganizationRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record CreateOrganizationRequest(@NotBlank String name, @NotBlank String slug)
```

### StatusRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record StatusRequest(OrganizationStatus status)
```

### RolesRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record RolesRequest(@NotEmpty Set<String> roles)
```

### CreateRoleRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record CreateRoleRequest(
            @NotBlank String name, @NotBlank String description, @NotEmpty Set<String> permissions)
```

### PermissionsRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record PermissionsRequest(@NotEmpty Set<String> permissions)
```

### InviteRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record InviteRequest(@Email @NotBlank String email, @NotEmpty Set<String> roles)
```

### AcceptInviteRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record AcceptInviteRequest(@NotBlank String displayName)
```

### ApiKeyRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record ApiKeyRequest(@NotBlank String name, @NotEmpty Set<String> scopes)
```

### ApiKeyValidationRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record ApiKeyValidationRequest(@NotBlank String apiKey)
```

### BreakGlassRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/IdentityController.java)

```java
record BreakGlassRequest(@NotBlank String requestedBy, @NotBlank String reason)
```

### Input

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/SamlController.java)

```java
record Input(long version, SamlConnection connection)
```

### Link

[Definition](../../identity-service/src/main/java/com/openhire/identity/adapter/in/web/SamlController.java)

```java
record Link(String memberId, String subject)
```

### Input

[Definition](../../identity-service/src/main/java/com/openhire/identity/application/ScimUsers.java)

```java
record Input(String userName, String displayName, String externalId, boolean active)
```

### AuthPolicy

[Definition](../../identity-service/src/main/java/com/openhire/identity/domain/IdentityModels.java)

```java
record AuthPolicy(
            boolean mfaRequired, int maxFailedAttempts, int lockoutMinutes, Set<String> allowedEmailDomains)
```

### SsoSettings

[Definition](../../identity-service/src/main/java/com/openhire/identity/domain/IdentityModels.java)

```java
record SsoSettings(boolean enabled, String protocol, String issuer, String clientId, boolean scimEnabled)
```

### TenantQuota

[Definition](../../identity-service/src/main/java/com/openhire/identity/domain/IdentityModels.java)

```java
record TenantQuota(int maxUsers, int maxApiKeys, int maxActiveJobs)
```

### BreakGlassRequest

[Definition](../../identity-service/src/main/java/com/openhire/identity/domain/IdentityModels.java)

```java
record BreakGlassRequest(
            String id,
            String organizationId,
            String requestedBy,
            String reason,
            String status,
            Instant requestedAt,
            Instant expiresAt,
            List<String> auditTrail)
```

### SamlConnection

[Definition](../../identity-service/src/main/java/com/openhire/identity/domain/SamlConnection.java)

```java
record SamlConnection(boolean enabled, String entityId, String ssoUrl, String signingCertificate)
```
