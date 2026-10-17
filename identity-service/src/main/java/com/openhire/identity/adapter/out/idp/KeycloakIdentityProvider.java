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
                        true,
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
        var available = keycloak.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm/available", realm, providerUserId)
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
