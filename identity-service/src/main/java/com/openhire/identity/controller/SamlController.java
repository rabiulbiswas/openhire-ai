package com.openhire.identity.controller;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.openhire.identity.domain.IdentityModels.OrganizationStatus;
import com.openhire.identity.domain.SamlConnection;
import com.openhire.identity.service.port.EnterpriseDirectory;
import com.openhire.identity.service.port.IdentityRepositories.Members;
import com.openhire.identity.service.port.IdentityRepositories.Organizations;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sso-config/saml")
public class SamlController {
    private final EnterpriseDirectory directory;
    private final MongoTemplate mongo;
    private final Members members;
    private final Organizations organizations;
    private final AuditRecorder audit;

    public SamlController(
            EnterpriseDirectory directory,
            MongoTemplate mongo,
            Members members,
            Organizations organizations,
            AuditRecorder audit) {
        this.directory = directory;
        this.mongo = mongo;
        this.members = members;
        this.organizations = organizations;
        this.audit = audit;
    }

    String tenant(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt)
                || auth.getAuthorities().stream().noneMatch(a -> Set.of("ROLE_ORG_ADMIN", "ROLE_SUPER_ADMIN")
                        .contains(a.getAuthority())))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Authenticated organization administrator required");
        String tenant =
                com.openhire.platform.tenant.TenantClaims.resolve(jwt.getToken().getClaims());
        if (tenant == null
                || organizations
                        .findById(tenant)
                        .filter(o -> o.status() == OrganizationStatus.ACTIVE)
                        .isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active organization required");
        return tenant;
    }

    static String alias(String tenant) {
        return "oh-saml-" + UUID.nameUUIDFromBytes(tenant.getBytes(StandardCharsets.UTF_8));
    }

    public record Input(long version, SamlConnection connection) {}

    @GetMapping
    Object get(Authentication auth) {
        String tenant = tenant(auth);
        var row = mongo.getCollection("saml_connections")
                .find(eq("_id", tenant))
                .projection(new Document("certificate", 0))
                .first();
        return row == null ? Map.of("configured", false, "version", 0, "alias", alias(tenant)) : row;
    }

    @PutMapping
    Object save(Authentication auth, @RequestBody Input input) {
        String tenant = tenant(auth);
        if (input.connection() == null || input.version() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connection and expected version required");
        var collection = mongo.getCollection("saml_connections");
        if (input.version() == 0) {
            try {
                collection.insertOne(
                        new Document("_id", tenant).append("version", 0L).append("state", "READY"));
            } catch (MongoWriteException duplicate) {
                if (duplicate.getError().getCode() != 11000) throw duplicate;
            }
        }
        if (collection
                        .updateOne(
                                and(eq("_id", tenant), eq("version", input.version()), ne("state", "UPDATING")),
                                new Document("$set", new Document("state", "UPDATING")))
                        .getModifiedCount()
                != 1)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Configuration changed or an update requires reconciliation");
        try {
            directory.configureSaml(alias(tenant), input.connection());
            collection.updateOne(
                    eq("_id", tenant),
                    new Document(
                                    "$set",
                                    new Document("configured", true)
                                            .append("alias", alias(tenant))
                                            .append(
                                                    "enabled",
                                                    input.connection().enabled())
                                            .append(
                                                    "entityId",
                                                    input.connection().entityId())
                                            .append("ssoUrl", input.connection().ssoUrl())
                                            .append(
                                                    "certificate",
                                                    input.connection().signingCertificate())
                                            .append("state", "READY"))
                            .append("$inc", new Document("version", 1L)));
            audit.record(AuditRecord.of(tenant, "SAML_PROVIDER_CONFIGURED")
                    .user(auth.getName(), null)
                    .resource("SAML_PROVIDER", alias(tenant))
                    .build());
            return get(auth);
        } catch (RuntimeException failure) {
            collection.updateOne(eq("_id", tenant), new Document("$set", new Document("state", "ERROR")));
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SAML configuration failed; verify Keycloak and the pre-linked-only flow before retrying");
        }
    }

    public record Link(String memberId, String subject) {}

    @PostMapping("/links")
    Object link(Authentication auth, @RequestBody Link input) {
        String tenant = tenant(auth);
        if (input.subject() == null
                || input.subject().isBlank()
                || input.subject().length() > 512
                || input.memberId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member and persistent SAML subject required");
        if (members.findMemberById(tenant, input.memberId()).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization member not found");
        if (mongo.getCollection("saml_connections")
                        .find(and(eq("_id", tenant), eq("configured", true), eq("state", "READY")))
                        .first()
                == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure the SAML provider first");
        directory.link(tenant, input.memberId(), alias(tenant), input.subject());
        audit.record(AuditRecord.of(tenant, "SAML_MEMBER_LINKED")
                .user(auth.getName(), null)
                .resource("USER", input.memberId())
                .build());
        return Map.of("linked", true);
    }
}
