package com.openhire.identity.adapter.out.idp;

import com.openhire.identity.application.port.EnterpriseDirectory;
import com.openhire.identity.domain.SamlConnection;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class KeycloakEnterpriseDirectory implements EnterpriseDirectory {
    private final RestClient client;
    private final String realm, clientId, secret, provider;
    private static final String FLOW = "openhire-prelinked-only";

    public KeycloakEnterpriseDirectory(
            RestClient.Builder builder,
            @Value("${openhire.keycloak.base-url}") String url,
            @Value("${openhire.keycloak.realm}") String realm,
            @Value("${openhire.keycloak.admin-client-id}") String clientId,
            @Value("${openhire.keycloak.admin-client-secret}") String secret,
            @Value("${openhire.identity.provider:local}") String provider) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.baseUrl(url).requestFactory(factory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.secret = secret;
        this.provider = provider;
    }

    private String token() {
        if (!"keycloak".equals(provider))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Enterprise identity requires Keycloak; local mode cannot simulate successful provisioning");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        var result = client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (result == null || !(result.get("access_token") instanceof String value))
            throw new IllegalStateException("Directory token unavailable");
        return value;
    }

    private String path(String suffix) {
        return "/admin/realms/" + realm + suffix;
    }

    private Map<?, ?> user(String token, String id) {
        return client.get()
                .uri(path("/users/{id}"), id)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
    }

    private void owns(Map<?, ?> user, String tenant) {
        if (user == null
                || !(user.get("attributes") instanceof Map<?, ?> attrs)
                || !List.of(tenant).equals(attrs.get("organization_id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Directory user is not owned by this organization");
    }

    @Override
    public String provision(String tenant, String stableId, String email, String displayName) {
        String token = token();
        String username = "scim." + stableId;
        try {
            client.post()
                    .uri(path("/users"))
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username",
                            username,
                            "email",
                            email,
                            "firstName",
                            displayName,
                            "enabled",
                            false,
                            "emailVerified",
                            false,
                            "attributes",
                            Map.of("organization_id", List.of(tenant), "openhire_scim_id", List.of(stableId))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
        }
        var users = client.get()
                .uri(builder -> builder.path(path("/users"))
                        .queryParam("username", username)
                        .queryParam("exact", true)
                        .build())
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(List.class);
        if (users == null || users.size() != 1 || !(users.getFirst() instanceof Map<?, ?> found))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Directory email or identity already exists");
        owns(found, tenant);
        if (!(found.get("attributes") instanceof Map<?, ?> attrs)
                || !List.of(stableId).equals(attrs.get("openhire_scim_id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot adopt an unmanaged directory identity");
        return String.valueOf(found.get("id"));
    }

    @Override
    public void update(String tenant, String id, String name, boolean active) {
        String token = token();
        owns(user(token, id), tenant);
        client.put()
                .uri(path("/users/{id}"), id)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("firstName", name, "enabled", active))
                .retrieve()
                .toBodilessEntity();
        if (!active)
            client.post()
                    .uri(path("/users/{id}/logout"), id)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
    }

    @Override
    public void configureSaml(String alias, SamlConnection connection) {
        String token = token();
        // Fail closed unless the operator installed the deny-first-login flow. Never silently use the default JIT flow.
        var executions = client.get()
                .uri(path("/authentication/flows/{flow}/executions"), FLOW)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(List.class);
        if (executions == null
                || executions.size() != 1
                || !(executions.getFirst() instanceof Map<?, ?> execution)
                || !"deny-access-authenticator".equals(execution.get("providerId"))
                || !"REQUIRED".equals(execution.get("requirement")))
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "Install the required pre-linked-only SAML authentication flow");
        var representation = samlRepresentation(alias, connection);
        try {
            client.post()
                    .uri(path("/identity-provider/instances"))
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            client.put()
                    .uri(path("/identity-provider/instances/{alias}"), alias)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    static Map<String, Object> samlRepresentation(String alias, SamlConnection value) {
        return Map.of(
                "alias",
                alias,
                "providerId",
                "saml",
                "enabled",
                value.enabled(),
                "trustEmail",
                false,
                "storeToken",
                false,
                "firstBrokerLoginFlowAlias",
                FLOW,
                "config",
                Map.of(
                        "idpEntityId",
                        value.entityId(),
                        "singleSignOnServiceUrl",
                        value.ssoUrl(),
                        "validateSignature",
                        "true",
                        "signingCertificate",
                        value.signingCertificate(),
                        "wantAuthnRequestsSigned",
                        "true",
                        "signatureAlgorithm",
                        "RSA_SHA256",
                        "postBindingResponse",
                        "true",
                        "postBindingAuthnRequest",
                        "true",
                        "syncMode",
                        "IMPORT"));
    }

    @Override
    public void link(String tenant, String providerId, String alias, String subject) {
        String token = token();
        owns(user(token, providerId), tenant);
        client.post()
                .uri(path("/users/{id}/federated-identity/{alias}"), providerId, alias)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("identityProvider", alias, "userId", subject, "userName", subject))
                .retrieve()
                .toBodilessEntity();
    }
}
