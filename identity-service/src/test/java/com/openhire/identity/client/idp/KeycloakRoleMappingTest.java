package com.openhire.identity.client.idp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KeycloakRoleMappingTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final KeycloakIdentityProvider provider = new KeycloakIdentityProvider(
            builder, "http://keycloak:8080/auth", "openhire-staff", "fixture", "fixture-secret");
    private final String userRoles =
            "http://keycloak:8080/auth/admin/realms/openhire-staff/users/fixture-user/role-mappings/realm";

    private void token() {
        server.expect(requestTo("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fixture-token\"}", MediaType.APPLICATION_JSON));
    }

    private void readRoles(String assigned, String available) {
        token();
        server.expect(requestTo(userRoles))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(assigned, MediaType.APPLICATION_JSON));
        token();
        server.expect(requestTo(userRoles + "/available"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(available, MediaType.APPLICATION_JSON));
    }

    @Test
    void assignsNewRoleAndRemovesOnlyReplacedTenantRolesWithoutRealmEnumeration() {
        readRoles(
                "[{\"id\":\"recruiter\",\"name\":\"RECRUITER\"},{\"id\":\"default\",\"name\":\"default-roles-openhire-staff\"}]",
                "[{\"id\":\"org-admin\",\"name\":\"ORG_ADMIN\"}]");
        token();
        server.expect(requestTo(userRoles))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("[{\"id\":\"recruiter\",\"name\":\"RECRUITER\"}]"))
                .andRespond(withNoContent());
        token();
        server.expect(requestTo(userRoles))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("[{\"id\":\"org-admin\",\"name\":\"ORG_ADMIN\"}]"))
                .andRespond(withNoContent());
        provider.updateMemberRoles("fixture-user", Set.of("ORG_ADMIN"));
        server.verify();
    }

    @Test
    void alreadyAssignedRoleIsUsableEvenWhenAbsentFromAvailableList() {
        readRoles("[{\"id\":\"org-admin\",\"name\":\"ORG_ADMIN\"}]", "[]");
        token();
        server.expect(requestTo(userRoles))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("[{\"id\":\"org-admin\",\"name\":\"ORG_ADMIN\"}]"))
                .andRespond(withNoContent());
        provider.updateMemberRoles("fixture-user", Set.of("ORG_ADMIN"));
        server.verify();
    }

    @Test
    void missingRoleFailsBeforeAnyRoleMutation() {
        readRoles("[{\"id\":\"recruiter\",\"name\":\"RECRUITER\"}]", "[]");
        assertThatThrownBy(() -> provider.updateMemberRoles("fixture-user", Set.of("ORG_ADMIN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not assignable");
        server.verify();
    }

    @Test
    void platformAndArbitraryRolesAreRejectedBeforeCallingKeycloak() {
        for (String role : Set.of("SUPER_ADMIN", "realm-admin", "UNCONFIGURED")) {
            assertThatThrownBy(() -> provider.updateMemberRoles("fixture-user", Set.of(role)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        server.verify();
    }
}
