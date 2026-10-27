package com.openhire.applicationservice.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.platform.security.CandidateIdentity;
import com.openhire.platform.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CandidateApplicationsControllerTest {
    private JwtAuthenticationToken user(String role, boolean verified) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "RS256").subject("subject")
                .claim("organization_id", "tenant").claim("email", "candidate@example.test").claim("email_verified", verified)
                .claim("realm_access", Map.of("roles", List.of(role))).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build(),
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
    }
    @Test void onlyVerifiedCandidatesCanReadTheirOwnApplicationStatus() throws Exception {
        var service = mock(SubmissionService.class); var mongo = mock(MongoTemplate.class); when(service.store()).thenReturn(mongo);
        var controller = new CandidateApplicationsController(service, "http://127.0.0.1:1", "fixture");
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/candidate/applications/status").param("jobId", "job")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/candidate/applications/status").param("jobId", "job").principal(user("RECRUITER", true))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/candidate/applications/status").param("jobId", "job").principal(user("CANDIDATE", false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/candidate/applications/status").param("jobId", "job").principal(user("CANDIDATE", true))).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
        var query = ArgumentCaptor.forClass(Query.class); verify(mongo).exists(query.capture(), eq("submissions"));
        assertThat(query.getValue().getQueryObject()).containsEntry("tenantId", "tenant").containsEntry("email", "candidate@example.test").containsEntry("jobId", "job").containsEntry("source", "CAREER");
    }
    @Test void newClientKeyCannotCreateAnotherApplicationToTheSameJob() throws Exception {
        var service = mock(SubmissionService.class); var mongo = mock(MongoTemplate.class); when(service.store()).thenReturn(mongo);
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("submissions"))).thenReturn(new Document("_id", "prior-application"));
        var mvc = MockMvcBuilders.standaloneSetup(new CandidateApplicationsController(service, "http://127.0.0.1:1", "fixture"))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(multipart("/api/v1/candidate/applications").file(new MockMultipartFile("resume", "resume.pdf", "application/pdf", "%PDF-1.7 fixture".getBytes()))
                .param("jobId", "job").param("receiptToken", "x".repeat(64)).header("Idempotency-Key", "different-key")
                .principal(user("CANDIDATE", true))).andExpect(status().isConflict());
        verify(service, never()).submit(anyString(), anyString(), anyString(), any(), any());
    }
}
