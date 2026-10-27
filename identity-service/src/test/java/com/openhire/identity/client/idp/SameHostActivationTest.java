package com.openhire.identity.client.idp;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SameHostActivationTest {
    @Test
    void serviceTokenAndActivationLinkUseTheSameValidatedTenantOrigin() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new KeycloakIdentityProvider(
                builder, "http://keycloak:8080/auth", "openhire-staff", "fixture-client", "fixture-secret");
        ReflectionTestUtils.setField(provider, "sameHostAuth", true);
        server.expect(requestTo("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Forwarded-Host", "codlogicx.localhost"))
                .andExpect(header("X-Forwarded-Proto", "http"))
                .andRespond(withSuccess("{\"access_token\":\"synthetic-service-token\"}", MediaType.APPLICATION_JSON));
        server.expect(
                        requestTo(
                                startsWith(
                                        "http://keycloak:8080/auth/admin/realms/openhire-staff/users/fixture-user/execute-actions-email?")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Forwarded-Host", "codlogicx.localhost"))
                .andExpect(header("Authorization", "Bearer synthetic-service-token"))
                .andExpect(content().json("[\"VERIFY_EMAIL\",\"UPDATE_PASSWORD\"]"))
                .andRespond(withNoContent());
        provider.sendActivation("fixture-user", "http://codlogicx.localhost");
        server.verify();
    }
}
