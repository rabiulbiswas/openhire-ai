package com.openhire.identity.adapter.out.idp;

import com.openhire.identity.application.port.IdentityProvider;
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
        var found = keycloak.get().uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("username", email).queryParam("exact", true).build(realm))
                .header("Authorization", "Bearer " + accessToken()).retrieve().body(List.class);
        if (found != null && !found.isEmpty()) {
            var user = (Map<?, ?>) found.getFirst();
            var attributes = user.get("attributes") instanceof Map<?, ?> values ? values : Map.of();
            if (!(attributes.get("organization_id") instanceof List<?> tenantIds)
                    || !tenantIds.equals(List.of(organizationId))) {
                throw new IllegalArgumentException("This email already belongs to another identity; manual membership review is required");
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
        if (roles.contains("SUPER_ADMIN")) throw new IllegalArgumentException("Tenant operations cannot assign SUPER_ADMIN");
        var available = keycloak.get()
                .uri("/admin/realms/{realm}/roles", realm)
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(List.class);
        var selected = available == null
                ? List.<Map<String, Object>>of()
                : ((List<Map<String, Object>>) available)
                        .stream()
                                .filter(role -> roles.contains(String.valueOf(role.get("name"))))
                                .toList();
        if (selected.size() != roles.size()) {
            throw new IllegalStateException("One or more Keycloak realm roles are not configured");
        }
        var assigned = keycloak.get().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, providerUserId)
                .header("Authorization", "Bearer " + accessToken()).retrieve().body(List.class);
        if (assigned != null) {
            var removed = ((List<Map<String, Object>>) assigned).stream()
                    .filter(role -> java.util.Set.of("ORG_ADMIN", "RECRUITER", "HR_ADMIN", "HIRING_MANAGER", "INTERVIEWER", "COMPLIANCE_OFFICER", "EMPLOYEE", "AGENCY_RECRUITER", "CANDIDATE").contains(String.valueOf(role.get("name"))))
                    .filter(role -> !roles.contains(String.valueOf(role.get("name")))).toList();
            if (!removed.isEmpty()) keycloak.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, providerUserId)
                    .header("Authorization", "Bearer " + accessToken()).contentType(MediaType.APPLICATION_JSON)
                    .body(removed).retrieve().toBodilessEntity();
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
    }

    @Override
    public void configureTenantLogin(String origin) {
        // Exact callback/logout allowlists are managed by a Keycloak operator.
        // This service deliberately has no client-management privileges.
        var uri = java.net.URI.create(origin);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !java.util.Set.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException("Invalid configured tenant origin");
        }
    }

    @Override
    public void sendActivation(String providerUserId, String origin) {
        keycloak.put().uri(builder -> builder.path("/admin/realms/{realm}/users/{id}/execute-actions-email")
                        .queryParam("client_id", "openhire-tenant").queryParam("redirect_uri", origin + "/login")
                        .queryParam("lifespan", 86400).build(realm, providerUserId))
                .header("Authorization", "Bearer " + accessToken()).contentType(MediaType.APPLICATION_JSON)
                .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD")).retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private String accessToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        var result = keycloak.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (result == null || result.get("access_token") == null)
            throw new IllegalStateException("Keycloak token request failed");
        return result.get("access_token").toString();
    }
}
