package com.openhire.platform.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class LocalPortalIssuerValidatorTest {
    private Jwt token(String issuer, String client, String role) {
        return Jwt.withTokenValue("synthetic")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject("fixture")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("azp", client)
                .claim("tenant_id", "456")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    @Test
    void acceptsTenantAndSeparatePlatformIssuers() {
        var validator = new LocalPortalIssuerValidator();
        assertThat(validator
                        .validate(token(
                                "http://codlogicx.localhost/auth/realms/openhire-staff",
                                "openhire-tenant",
                                "RECRUITER"))
                        .hasErrors())
                .isFalse();
        assertThat(validator
                        .validate(token(
                                "http://admin.localhost/auth/realms/openhire-staff",
                                "openhire-platform",
                                "SUPER_ADMIN"))
                        .hasErrors())
                .isFalse();
    }

    @Test
    void rejectsUnknownOriginsReservedHostsPortsAndWrongRealm() {
        for (String issuer : List.of(
                "http://auth.localhost/auth/realms/openhire-staff",
                "http://evil.example/auth/realms/openhire-staff",
                "http://a.b.localhost/auth/realms/openhire-staff",
                "http://codlogicx.localhost:8180/auth/realms/openhire-staff",
                "http://codlogicx.localhost/auth/realms/master",
                "http://codlogicx.localhost/realms/openhire-staff")) {
            assertThat(new LocalPortalIssuerValidator()
                            .validate(token(issuer, "openhire-tenant", "RECRUITER"))
                            .hasErrors())
                    .isTrue();
        }
    }

    @Test
    void rejectsCrossPortalRolesAndClients() {
        var validator = new LocalPortalIssuerValidator();
        assertThat(validator
                        .validate(token(
                                "http://codlogicx.localhost/auth/realms/openhire-staff",
                                "openhire-platform",
                                "SUPER_ADMIN"))
                        .hasErrors())
                .isTrue();
        assertThat(validator
                        .validate(token(
                                "http://admin.localhost/auth/realms/openhire-staff", "openhire-tenant", "RECRUITER"))
                        .hasErrors())
                .isTrue();
    }

    @Test
    void rejectsConflictingTenantClaims() {
        var original = token("http://codlogicx.localhost/auth/realms/openhire-staff", "openhire-tenant", "RECRUITER");
        var conflict = Jwt.withTokenValue("fixture")
                .headers(h -> h.putAll(original.getHeaders()))
                .claims(c -> {
                    c.putAll(original.getClaims());
                    c.put("organization_id", "another");
                })
                .build();
        assertThat(new LocalPortalIssuerValidator().validate(conflict).hasErrors())
                .isTrue();
    }

    @Test
    void signatureAndExpiryRemainMandatory() throws Exception {
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                .build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                org.springframework.security.oauth2.jwt.JwtValidators.createDefault(),
                new LocalPortalIssuerValidator()));
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer("http://codlogicx.localhost/auth/realms/openhire-staff")
                .subject("fixture")
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .claim("azp", "openhire-tenant")
                .claim("tenant_id", "456")
                .claim("realm_access", Map.of("roles", List.of("RECRUITER")))
                .build();
        var signed = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims);
        signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        assertThat(decoder.decode(signed.serialize()).getSubject()).isEqualTo("fixture");
        var wrongKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        var forged = new com.nimbusds.jwt.SignedJWT(signed.getHeader(), claims);
        forged.sign(new com.nimbusds.jose.crypto.RSASSASigner(wrongKey));
        assertThatThrownBy(() -> decoder.decode(forged.serialize()))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        var expired = new com.nimbusds.jwt.SignedJWT(
                signed.getHeader(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(claims)
                        .expirationTime(java.util.Date.from(Instant.now().minusSeconds(300)))
                        .build());
        expired.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        assertThatThrownBy(() -> decoder.decode(expired.serialize()))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
}
