package com.openhire.identity.adapter.in.web;

import com.openhire.identity.application.ScimUsers;
import com.openhire.identity.application.port.IdentityRepositories.*;
import com.openhire.identity.domain.IdentityModels.OrganizationStatus;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/api/v1/scim/v2", produces = "application/scim+json")
public class ScimController {
    private final ScimUsers users;
    private final TenantSettings settings;
    private final Organizations organizations;

    public ScimController(ScimUsers users, TenantSettings settings, Organizations organizations) {
        this.users = users;
        this.settings = settings;
        this.organizations = organizations;
    }

    String tenant(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt)
                || !jwt.getToken().getAudience().contains("openhire-scim")
                || !Arrays.asList(Optional.ofNullable(jwt.getToken().getClaimAsString("scope"))
                                .orElse("")
                                .split(" "))
                        .contains("scim.provision"))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "SCIM audience and delegated provisioning scope required");
        String tenant = jwt.getToken().getClaimAsString("organization_id");
        if (tenant == null
                || tenant.isBlank()
                || organizations
                        .findById(tenant)
                        .filter(o -> o.status() == OrganizationStatus.ACTIVE)
                        .isEmpty()
                || settings.findSettings(tenant)
                        .filter(s -> s.sso().scimEnabled())
                        .isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Provisioning is not enabled for this organization");
        return tenant;
    }

    @GetMapping("/ServiceProviderConfig")
    Map<String, Object> config(Authentication auth) {
        tenant(auth);
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"),
                "patch",
                Map.of("supported", true),
                "bulk",
                Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
                "filter",
                Map.of("supported", true, "maxResults", 200),
                "changePassword",
                Map.of("supported", false),
                "sort",
                Map.of("supported", false),
                "etag",
                Map.of("supported", true),
                "authenticationSchemes",
                List.of(Map.of(
                        "type",
                        "oauthbearertoken",
                        "name",
                        "OAuth bearer token",
                        "description",
                        "Tenant-scoped scim.provision access token")));
    }

    @GetMapping("/ResourceTypes")
    Map<String, Object> resources(Authentication auth) {
        tenant(auth);
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"),
                "totalResults",
                1,
                "itemsPerPage",
                1,
                "startIndex",
                1,
                "Resources",
                List.of(Map.of(
                        "schemas",
                        List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
                        "id",
                        "User",
                        "name",
                        "User",
                        "endpoint",
                        "/Users",
                        "schema",
                        ScimUsers.USER_SCHEMA)));
    }

    private Map<String, Object> userSchema() {
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:schemas:core:2.0:Schema"),
                "id",
                ScimUsers.USER_SCHEMA,
                "name",
                "User",
                "description",
                "OpenHire managed user profile; groups, passwords and role assignment are unsupported",
                "attributes",
                List.of(
                        attribute("userName", "string", true, "immutable", "server"),
                        attribute("displayName", "string", true, "readWrite", "none"),
                        attribute("active", "boolean", false, "readWrite", "none")));
    }

    private Map<String, Object> attribute(
            String name, String type, boolean required, String mutability, String uniqueness) {
        return Map.of(
                "name",
                name,
                "type",
                type,
                "multiValued",
                false,
                "required",
                required,
                "caseExact",
                false,
                "mutability",
                mutability,
                "returned",
                "default",
                "uniqueness",
                uniqueness);
    }

    @GetMapping("/ResourceTypes/User")
    Object userResource(Authentication auth) {
        tenant(auth);
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
                "id",
                "User",
                "name",
                "User",
                "endpoint",
                "/Users",
                "schema",
                ScimUsers.USER_SCHEMA);
    }

    @GetMapping("/Schemas")
    Object schemas(Authentication auth) {
        tenant(auth);
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"),
                "totalResults",
                1,
                "startIndex",
                1,
                "itemsPerPage",
                1,
                "Resources",
                List.of(userSchema()));
    }

    @GetMapping("/Schemas/{id}")
    Object schema(Authentication auth, @PathVariable String id) {
        tenant(auth);
        if (!ScimUsers.USER_SCHEMA.equals(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schema not found");
        return userSchema();
    }

    @GetMapping("/Users")
    Object list(
            Authentication auth,
            @RequestParam(defaultValue = "1") int startIndex,
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(required = false) String filter) {
        return users.list(tenant(auth), startIndex, count, filter);
    }

    @GetMapping("/Users/{id}")
    ResponseEntity<?> get(Authentication auth, @PathVariable String id) {
        return result(users.get(tenant(auth), id), HttpStatus.OK);
    }

    @PostMapping("/Users")
    ResponseEntity<?> create(Authentication auth, @RequestBody Map<String, Object> body) {
        return result(users.create(tenant(auth), auth.getName(), input(body)), HttpStatus.CREATED);
    }

    @PutMapping("/Users/{id}")
    ResponseEntity<?> replace(
            Authentication auth,
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String etag) {
        return result(users.update(tenant(auth), auth.getName(), id, input(body), etag, false), HttpStatus.OK);
    }

    @PatchMapping("/Users/{id}")
    ResponseEntity<?> patch(
            Authentication auth,
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String etag) {
        String tenant = tenant(auth);
        if (!List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp").equals(body.get("schemas"))
                || !(body.get("Operations") instanceof List<?> operations)
                || operations.isEmpty()
                || operations.size() > 20) throw ScimUsers.bad("SCIM PatchOp with 1–20 operations required");
        var current = new HashMap<>(users.get(tenant, id));
        String readVersion = String.valueOf(((Map<?, ?>) current.get("meta")).get("version"));
        for (var item : operations) {
            if (!(item instanceof Map<?, ?> op)
                    || !(op.get("op") instanceof String action)
                    || !(action.equalsIgnoreCase("replace") || action.equalsIgnoreCase("add")))
                throw ScimUsers.bad("Only add/replace of active or displayName is supported");
            if (op.get("path") == null && op.get("value") instanceof Map<?, ?> values) {
                for (var entry : values.entrySet())
                    patchValue(current, String.valueOf(entry.getKey()), entry.getValue());
            } else patchValue(current, String.valueOf(op.get("path")), op.get("value"));
        }
        return result(
                users.update(tenant, auth.getName(), id, input(current), etag == null ? readVersion : etag, false),
                HttpStatus.OK);
    }

    static void patchValue(Map<String, Object> values, String path, Object value) {
        if (path.equalsIgnoreCase("active") && value instanceof Boolean) values.put("active", value);
        else if (path.equalsIgnoreCase("displayName") && value instanceof String) values.put("displayName", value);
        else throw ScimUsers.bad("Only active and displayName can be patched; roles cannot be provisioned");
    }

    static ScimUsers.Input input(Map<String, Object> value) {
        if (value.containsKey("roles") || value.containsKey("groups") || value.containsKey("password"))
            throw ScimUsers.bad("Roles, groups and passwords are not provisioned through this endpoint");
        if (!(value.get("userName") instanceof String name)
                || !(value.get("displayName") instanceof String display)
                || !(value.get("externalId") instanceof String external)
                || (value.containsKey("active") && !(value.get("active") instanceof Boolean)))
            throw ScimUsers.bad("userName, displayName, externalId and a boolean active value required");
        return new ScimUsers.Input(name, display, external, !Boolean.FALSE.equals(value.get("active")));
    }

    @DeleteMapping("/Users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            Authentication auth,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String etag) {
        users.delete(tenant(auth), auth.getName(), id, etag);
    }

    private ResponseEntity<?> result(Map<String, Object> value, HttpStatus status) {
        return ResponseEntity.status(status)
                .eTag(String.valueOf(((Map<?, ?>) value.get("meta")).get("version")))
                .header("Location", "/api/v1/scim/v2/Users/" + value.get("id"))
                .body(value);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of(
                        "schemas",
                        List.of("urn:ietf:params:scim:api:messages:2.0:Error"),
                        "status",
                        Integer.toString(error.getStatusCode().value()),
                        "detail",
                        Optional.ofNullable(error.getReason()).orElse("SCIM request rejected")));
    }
}
