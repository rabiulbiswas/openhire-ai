package com.openhire.integration.security;

import com.openhire.integration.service.CalendarPorts;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Read-only mounted secrets. References resolve only beneath the administrator-configured root. */
@Component
public class FileIntegrationSecrets implements CalendarPorts.Secrets {
    private final String directory;
    private final ObjectMapper json;

    public FileIntegrationSecrets(
            @Value("${openhire.integration.secrets-directory:}") String directory, ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    public String read(String reference) {
        if (reference == null || !reference.matches("secret://[A-Za-z0-9][A-Za-z0-9/_-]{0,199}"))
            throw new IllegalArgumentException("Invalid secret reference");
        try {
            if (directory.isBlank()) throw new java.io.IOException();
            Path root = Path.of(directory).toRealPath();
            Path file = root.resolve(reference.substring(9) + ".json").toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 16384)
                throw new java.io.IOException();
            return Files.readString(file);
        } catch (java.io.IOException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Integration secret reference is not configured");
        }
    }

    public CalendarPorts.Credentials credentials(String reference) {
        try {
            var c = json.readValue(read(reference), CalendarPorts.Credentials.class);
            if (c.clientId() == null
                    || c.clientId().isBlank()
                    || c.clientSecret() == null
                    || c.clientSecret().isBlank()) throw new IllegalArgumentException();
            var redirect = java.net.URI.create(c.redirectUri());
            boolean local = "http".equals(redirect.getScheme()) && "localhost".equals(redirect.getHost());
            if ((!"https".equals(redirect.getScheme()) && !local)
                    || redirect.getHost() == null
                    || redirect.getUserInfo() != null
                    || redirect.getFragment() != null
                    || redirect.getQuery() != null) throw new IllegalArgumentException();
            if (c.directory() != null && !c.directory().matches("organizations|[a-fA-F0-9-]{36}"))
                throw new IllegalArgumentException();
            return c;
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Integration OAuth configuration is invalid");
        }
    }
}
