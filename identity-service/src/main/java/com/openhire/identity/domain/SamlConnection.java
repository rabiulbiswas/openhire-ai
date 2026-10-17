package com.openhire.identity.domain;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

public record SamlConnection(boolean enabled, String entityId, String ssoUrl, String signingCertificate) {
    public SamlConnection {
        if (entityId == null || entityId.isBlank() || entityId.length() > 2048)
            throw new IllegalArgumentException("SAML entity ID required");
        URI uri;
        try {
            uri = URI.create(ssoUrl);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("HTTPS SSO URL required");
        }
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || ssoUrl.length() > 2048)
            throw new IllegalArgumentException("HTTPS SSO URL without credentials required");
        try {
            if (signingCertificate == null || signingCertificate.length() > 16000) throw new IllegalArgumentException();
            byte[] bytes = signingCertificate.contains("BEGIN CERTIFICATE")
                    ? signingCertificate.getBytes(StandardCharsets.US_ASCII)
                    : Base64.getDecoder().decode(signingCertificate);
            var certificate = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
            certificate.checkValidity();
            if (certificate.getPublicKey() instanceof java.security.interfaces.RSAKey rsa
                    && rsa.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            if (certificate.getPublicKey() instanceof java.security.interfaces.ECKey ec
                    && ec.getParams().getOrder().bitLength() < 256) throw new IllegalArgumentException();
            String algorithm = certificate.getSigAlgName().toUpperCase(java.util.Locale.ROOT);
            if (algorithm.contains("SHA1") || algorithm.contains("MD5")) throw new IllegalArgumentException();
            signingCertificate = Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "A currently valid X.509 signing certificate with a modern signature is required");
        }
    }
}
