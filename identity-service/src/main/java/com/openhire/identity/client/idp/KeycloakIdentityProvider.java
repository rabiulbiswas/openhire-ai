package com.openhire.identity.client.idp;

import com.openhire.identity.service.port.IdentityProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "openhire.identity.provider", havingValue = "keycloak")
public class KeycloakIdentityProvider implements IdentityProvider {
    private static final Set<String> TENANT_ROLES = Set.of(
            "ORG_ADMIN",
            "RECRUITER",
            "HR_ADMIN",
            "HIRING_MANAGER",
            "INTERVIEWER",
            "EMPLOYEE",
            "AGENCY_RECRUITER",
            "CANDIDATE");

    @Value("${openhire.portals.same-host-auth:false}")
    private boolean sameHostAuth;

    private final RestClient keycloak;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakIdentityProvider(
            RestClient.Builder builder,
            @Value("${openhire.keycloak.base-url}") String baseUrl,
            @Value("${openhire.keycloak.realm:openhire-staff}") String realm,
            @Value("${openhire.keycloak.admin-client-id}") String clientId,
            @Value("${openhire.keycloak.admin-client-secret}") String clientSecret) {
        this.keycloak = builder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String provisionOrganization(String organizationId, String name) {
        return organizationId;
    }

    @Override
    public String provisionMember(String organizationId, String email, String displayName, Set<String> roles) {
        // Recover safely after a provider success followed by a database/network failure.
        var found = keycloak.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("username", email)
                        .queryParam("exact", true)
                        .build(realm))
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(List.class);
        if (found != null && !found.isEmpty()) {
            var user = (Map<?, ?>) found.getFirst();
            var attributes = user.get("attributes") instanceof Map<?, ?> values ? values : Map.of();
            if (!(attributes.get("organization_id") instanceof List<?> tenantIds)
                    || !tenantIds.equals(List.of(organizationId))) {
                throw new IllegalArgumentException(
                        "This email already belongs to another identity; manual membership review is required");
            }
            var id = user.get("id").toString();
            updateMemberRoles(id, roles);
            return id;
        }
        var response = keycloak.post()
                .uri("/admin/realms/{realm}/users", realm)
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username",
                        email,
                        "email",
                        email,
                        "firstName",
                        displayName,
                        "enabled",
                        true,
                        "emailVerified",
                        false,
                        "attributes",
                        Map.of("organization_id", List.of(organizationId))))
                .retrieve()
                .toBodilessEntity();
        var location = response.getHeaders().getLocation();
        if (location == null) throw new IllegalStateException("Keycloak did not return the new user identifier");
        var userId = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
        updateMemberRoles(userId, roles);
        return userId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateMemberRoles(String providerUserId, Set<String> roles) {
        if (!TENANT_ROLES.containsAll(roles))
            throw new IllegalArgumentException(
                    "Tenant operations can assign only supported tenant roles, never SUPER_ADMIN");
        // Realm-wide role enumeration requires extra privileges. User-scoped mappings provide
        // both already assigned roles and the roles this service is allowed to assign.
        var assigned = keycloak.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, providerUserId)
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(List.class);
        var available = keycloak.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm/available", realm, providerUserId)
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(List.class);
        var candidates = new java.util.LinkedHashMap<String, Map<String, Object>>();
        for (var source : List.of(assigned == null ? List.of() : assigned, available == null ? List.of() : available)) {
            for (var role : (List<Map<String, Object>>) source) {
                var name = String.valueOf(role.get("name"));
                if (roles.contains(name)) candidates.put(name, role);
            }
        }
        if (candidates.size() != roles.size())
            throw new IllegalStateException(
                    "A requested tenant role is missing or is not assignable by the identity service");
        var selected = List.copyOf(candidates.values());
        if (assigned != null) {
            var removed = ((List<Map<String, Object>>) assigned)
                    .stream()
                            .filter(role -> TENANT_ROLES.contains(String.valueOf(role.get("name"))))
                            .filter(role -> !roles.contains(String.valueOf(role.get("name"))))
                            .toList();
            if (!removed.isEmpty())
                keycloak.method(org.springframework.http.HttpMethod.DELETE)
                        .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, providerUserId)
                        .header("Authorization", "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(removed)
                        .retrieve()
                        .toBodilessEntity();
        }
        keycloak.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, providerUserId)
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(selected)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void disableMember(String providerUserId) {
        keycloak.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, providerUserId)
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false))
                .retrieve()
                .toBodilessEntity();
        keycloak.post()
                .uri("/admin/realms/{realm}/users/{id}/logout", realm, providerUserId)
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public String registerVerifiedCandidate(String tenant, String email, String name, String password, String registration) {
        // Never call provisionMember here: it may change roles on an existing staff identity.
        var found = keycloak.get().uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("username", email).queryParam("exact", true).build(realm))
                .header("Authorization", "Bearer " + accessToken()).retrieve().body(List.class);
        String id;
        if (found != null && !found.isEmpty()) {
            var user = (Map<?, ?>) found.getFirst();
            var attrs = user.get("attributes") instanceof Map<?, ?> attributes ? attributes : Map.of();
            if (!List.of(tenant).equals(attrs.get("organization_id"))
                    || !List.of(registration).equals(attrs.get("candidate_registration_id")))
                throw new IllegalArgumentException("Account already exists; sign in or use account recovery");
            id = user.get("id").toString();
            var assigned = keycloak.get().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, id)
                    .header("Authorization", "Bearer " + accessToken()).retrieve().body(List.class);
            if (assigned != null && assigned.stream().anyMatch(value -> {
                var role = String.valueOf(((Map<?, ?>) value).get("name"));
                return role.equals("SUPER_ADMIN") || (TENANT_ROLES.contains(role) && !role.equals("CANDIDATE"));
            })) throw new IllegalArgumentException("This identity cannot be registered as a candidate");
            // Retry of our own partial creation only. Do not reset any existing password.
        } else {
            int space = name.indexOf(' ');
            var response = keycloak.post().uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + accessToken()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", email, "email", email, "firstName", space < 0 ? name : name.substring(0, space),
                            "lastName", space < 0 ? "" : name.substring(space + 1), "enabled", false,
                            "emailVerified", true, "requiredActions", List.of(),
                            "attributes", Map.of("organization_id", List.of(tenant), "candidate_registration_id", List.of(registration)),
                            "credentials", List.of(Map.of("type", "password", "temporary", false, "value", password))))
                    .retrieve().toBodilessEntity();
            var location = response.getHeaders().getLocation();
            if (location == null) throw new IllegalStateException("Identity provider did not return an identifier");
            id = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
        }
        updateMemberRoles(id, Set.of("CANDIDATE"));
        keycloak.put().uri("/admin/realms/{realm}/users/{id}", realm, id)
                .header("Authorization", "Bearer " + accessToken()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", true, "emailVerified", true)).retrieve().toBodilessEntity();
        return id;
    }

    @Override
    public void configureTenantLogin(String origin) {
        // Exact callback/logout allowlists are managed by a Keycloak operator.
        // This service deliberately has no client-management privileges.
        var uri = java.net.URI.create(origin);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !java.util.Set.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException("Invalid configured tenant origin");
        }
    }

    @Override
    public void sendActivation(String providerUserId, String origin) {
        configureTenantLogin(origin);
        var portal = java.net.URI.create(origin);
        keycloak.put()
                .uri(builder -> builder.path("/admin/realms/{realm}/users/{id}/execute-actions-email")
                        .queryParam("client_id", "openhire-tenant")
                        .queryParam("redirect_uri", origin + "/login")
                        .queryParam("lifespan", 86400)
                        .build(realm, providerUserId))
                .header("Authorization", "Bearer " + accessToken(sameHostAuth ? portal : null))
                .headers(headers -> portalHeaders(headers, sameHostAuth ? portal : null))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"))
                .retrieve()
                .toBodilessEntity();
    }

    private String accessToken() {
        return accessToken(null);
    }

    private void portalHeaders(org.springframework.http.HttpHeaders headers, java.net.URI portal) {
        if (portal == null) return;
        if (!"http".equals(portal.getScheme())
                || portal.getHost() == null
                || !portal.getHost().matches("[a-z0-9]+(?:-[a-z0-9]+)*\\.localhost")
                || (portal.getPort() != -1 && portal.getPort() != 80))
            throw new IllegalArgumentException("Invalid local activation origin");
        headers.remove("Forwarded");
        headers.remove("X-Forwarded-Prefix");
        headers.remove("X-Forwarded-Ssl");
        headers.set("X-Forwarded-Host", portal.getHost());
        headers.set("X-Forwarded-Proto", "http");
        headers.set("X-Forwarded-Port", "80");
    }

    @SuppressWarnings("unchecked")
    private String accessToken(java.net.URI portal) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        var result = keycloak.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .headers(headers -> portalHeaders(headers, portal))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (result == null || result.get("access_token") == null)
            throw new IllegalStateException("Keycloak token request failed");
        return result.get("access_token").toString();
    }
}
