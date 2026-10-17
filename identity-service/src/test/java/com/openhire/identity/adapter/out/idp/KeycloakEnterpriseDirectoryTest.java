package com.openhire.identity.adapter.out.idp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.identity.domain.SamlConnection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class KeycloakEnterpriseDirectoryTest {
    @Test
    void samlRequiresSignedAssertionsAndDoesNotTrustEmail() {
        var input = mock(SamlConnection.class);
        when(input.enabled()).thenReturn(true);
        when(input.entityId()).thenReturn("urn:test:idp");
        when(input.ssoUrl()).thenReturn("https://idp.example/sso");
        when(input.signingCertificate()).thenReturn("test-public-certificate");
        var representation = KeycloakEnterpriseDirectory.samlRepresentation("tenant-alias", input);
        var config = (Map<?, ?>) representation.get("config");
        assertEquals("true", config.get("validateSignature"));
        assertEquals("RSA_SHA256", config.get("signatureAlgorithm"));
        assertEquals(false, representation.get("trustEmail"));
        assertEquals("openhire-prelinked-only", representation.get("firstBrokerLoginFlowAlias"));
        assertEquals("urn:test:idp", config.get("idpEntityId"));
    }

    @Test
    void invalidCertificatesAndHttpEndpointsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SamlConnection(true, "issuer", "http://idp.example/sso", "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SamlConnection(true, "issuer", "https://idp.example/sso", "bad"));
    }

    @Test
    void localModeNeverPretendsToProvisionEnterpriseIdentities() {
        var client = new KeycloakEnterpriseDirectory(
                RestClient.builder(), "http://localhost:1", "realm", "client", "not-a-live-secret", "local");
        assertThrows(ResponseStatusException.class, () -> client.provision("t", "id", "staff@example.com", "Staff"));
    }
}
