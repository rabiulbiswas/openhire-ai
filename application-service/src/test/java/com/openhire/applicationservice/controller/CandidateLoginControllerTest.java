package com.openhire.applicationservice.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import com.openhire.support.Records;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CandidateLoginControllerTest {
    final SubmissionService service = mock(SubmissionService.class);
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final Access access = new Access("oidc", "", "secret");
    final CandidateLoginController controller = new CandidateLoginController(service, access);

    JwtAuthenticationToken auth(String role, boolean verified) {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("test")
                        .header("alg", "RS256")
                        .subject("candidate")
                        .claim("tenant_id", "tenant-a")
                        .claim("email", "DEMO@example.test")
                        .claim("email_verified", verified)
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void loginScopesToVerifiedEmailAndTenantAndDoesNotReplaceOriginalReceipt() {
        when(service.store()).thenReturn(mongo);
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("submissions")))
                .thenReturn(new Document("_id", "own-app"));
        when(service.key("tenant-a", "own-app")).thenReturn(new Query());
        var result = controller.open(auth("CANDIDATE", true));
        assertThat(result).containsEntry("id", "own-app").containsEntry("tenantId", "tenant-a");
        var scope = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(scope.capture(), eq(Document.class), eq("submissions"));
        assertThat(scope.getValue().getQueryObject().toString())
                .contains("tenant-a", "demo@example.test", "CAREER", "processingConsent=true");
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq("submissions"));
        assertThat(update.getValue().getUpdateObject().toString())
                .contains("portalReceipts", "expiresAt", "$slice", "-5")
                .doesNotContain("receiptHash", result.get("token").toString());
    }

    @Test
    void staffAndUnverifiedCandidatesCannotObtainReceipts() {
        assertThatThrownBy(() -> controller.open(auth("RECRUITER", true))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.open(auth("CANDIDATE", false))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    void expiredLoginReceiptsAreRejectedAndOriginalLinksStillWork() {
        var row = new Document("receiptHash", Records.hash("original"))
                .append(
                        "portalReceipts",
                        List.of(
                                new Document("hash", Records.hash("expired"))
                                        .append(
                                                "expiresAt",
                                                Instant.now().minusSeconds(1).toString()),
                                new Document("hash", Records.hash("valid"))
                                        .append(
                                                "expiresAt",
                                                Instant.now().plusSeconds(60).toString())));
        when(service.get("tenant-a", "app")).thenReturn(row);
        var intake = new SubmissionController(service, access);
        assertThatThrownBy(() -> intake.status("app", "tenant-a", "expired"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(intake.status("app", "tenant-a", "original")).isNotNull();
        assertThat(intake.status("app", "tenant-a", "valid")).isNotNull();
    }
}
