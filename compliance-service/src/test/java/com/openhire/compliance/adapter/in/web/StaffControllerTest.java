package com.openhire.compliance.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.compliance.application.Rig;
import com.openhire.platform.web.GlobalExceptionHandler;
import com.openhire.support.Access;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class StaffControllerTest {
    private static final String SECRET = "test-secret";
    private final ObjectMapper json = new ObjectMapper();
    private Rig rig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        rig = new Rig();
        var access = new Access("oidc", "demo", SECRET);
        var viewers = new Viewers(access, "oidc");
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuditController(rig.ledger, viewers, json),
                        new StaffController(
                                viewers,
                                rig.history,
                                rig.consent,
                                rig.privacy,
                                rig.holds,
                                rig.retention,
                                rig.fairness,
                                rig.overview),
                        new PublicController(rig.privacy, rig.selfIdentification, rig.consent, access))
                .setControllerAdvice(new ComplianceErrors(), new GlobalExceptionHandler())
                .build();
        rig.directory.add("app_1", "cand_1", "Asha", "job_1", "REJECTED");
        rig.submitted("app_1", "job_1", "2026-01-01T08:00:00Z");
        rig.stage("app_1", "APPLIED", "REJECTED", "rec", "2026-01-02T10:00:00Z");
        rig.ledger.ingest(Rig.auditEvent("t1", "OFFER_SENT", "app_1", "cand_1", "2026-10-01T10:00:00Z"));
        rig.ledger.ingest(Rig.auditEvent("t2", "OFFER_SENT", "app_9", "cand_9", "2026-10-01T10:00:00Z"));
    }

    private static JwtAuthenticationToken as(String tenant, String... roles) {
        var jwt = new Jwt(
                "t",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "user-1", "organization_id", tenant, "name", "Dev"));
        return new JwtAuthenticationToken(
                jwt,
                Arrays.stream(roles)
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    @Test
    void everyStaffEndpointRefusesARoleWithoutComplianceAccess() throws Exception {
        var recruiter = as("t1", "RECRUITER");
        for (var path : new String[] {
            "/api/v1/audit-events",
            "/api/v1/audit-events/verify",
            "/api/v1/audit-events/export",
            "/api/v1/ai-decisions",
            "/api/v1/ai-decisions/x",
            "/api/v1/trace/applications/app_1",
            "/api/v1/consent-texts",
            "/api/v1/consent-records?candidateId=c",
            "/api/v1/privacy/requests",
            "/api/v1/legal-holds",
            "/api/v1/retention-policies",
            "/api/v1/reports/fairness",
            "/api/v1/compliance/overview"
        }) mvc.perform(get(path).principal(recruiter)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/privacy/requests")
                        .principal(recruiter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"cand_1\",\"type\":\"ERASURE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/legal-holds")
                        .principal(recruiter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"cand_1\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/retention-policies/run").principal(recruiter)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/retention-policies/candidate.non-hired")
                        .principal(recruiter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionMonths\":12,\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorsReadButOnlyOfficersAndAdminsManage() throws Exception {
        var superAdmin = as("t1", "SUPER_ADMIN");
        mvc.perform(get("/api/v1/audit-events").principal(superAdmin)).andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/consent-texts")
                                .principal(superAdmin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"purpose\":\"PROCESSING\",\"locale\":\"en\",\"text\":\"I agree to the processing of my application.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/consent-texts")
                                .principal(as("t1", "COMPLIANCE_OFFICER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"purpose\":\"PROCESSING\",\"locale\":\"en\",\"text\":\"I agree to the processing of my application.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("privacy-v2"));
    }

    @Test
    void theAuditListIsTenantScopedAndPaged() throws Exception {
        mvc.perform(get("/api/v1/audit-events").principal(as("t1", "COMPLIANCE_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].resource.id").value("off_1"))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/audit-events/verify").principal(as("t1", "ORG_ADMIN")))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checked").value(1));
    }

    @Test
    void exportStreamsNdjsonAndAuditsTheExport() throws Exception {
        var started = mvc.perform(get("/api/v1/audit-events/export").principal(as("t1", "ORG_ADMIN")))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andExpect(content().string(org.hamcrest.Matchers.endsWith("\n")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"action\":\"OFFER_SENT\"")));
        org.assertj.core.api.Assertions.assertThat(rig.sink.of("DATA_EXPORT")).hasSize(1);
    }

    @Test
    void otherTenantsCannotSeePrivacyRequestsHoldsOrDecisions() throws Exception {
        var created = mvc.perform(post("/api/v1/privacy/requests")
                        .principal(as("t1", "COMPLIANCE_OFFICER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"cand_1\",\"type\":\"EXPORT\",\"reason\":\"asked\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var id = json.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asString();
        var other = as("t2", "COMPLIANCE_OFFICER");

        mvc.perform(get("/api/v1/privacy/requests/" + id).principal(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/privacy/requests").principal(other))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/legal-holds").principal(other))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/ai-decisions/asm_1").principal(other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/legal-holds/hld_x/release")
                        .principal(other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retentionRunDefaultsToADryRunAndHonoursAsOf() throws Exception {
        mvc.perform(post("/api/v1/retention-policies/run")
                        .param("asOf", "2030-01-01T00:00:00Z")
                        .principal(as("t1", "ORG_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.evaluatedAt").value("2030-01-01T00:00:00.000Z"))
                .andExpect(jsonPath("$.policies[1].wouldErase.length()").value(1));
    }

    @Test
    void candidateEndpointsNeedTheReceiptAndRejectABadOne() throws Exception {
        mvc.perform(post("/api/v1/public/applications/app_1/privacy-requests")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPORT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/public/applications/app_1/privacy-requests")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "receipt-app_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPORT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.participants").doesNotExist());
        mvc.perform(put("/api/v1/public/applications/app_1/self-identification")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "receipt-app_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\":\"female\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").value("FEMALE"));
        mvc.perform(get("/api/v1/public/applications/app_1/self-identification")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "nope"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anExpiredExportIsGoneAndAnUnreadyOneIsAConflict() throws Exception {
        var request = rig.privacy.createByCandidate("t1", "app_1", "receipt-app_1", "EXPORT");
        var id = (String) request.get("id");
        mvc.perform(get("/api/v1/public/privacy-requests/" + id + "/export")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "receipt-app_1"))
                .andExpect(status().isConflict());
        for (var service : rig.settings.participants())
            rig.privacy.onAck(Rig.envelope(
                    "privacy.export.completed.v1",
                    "t1",
                    id,
                    "2026-10-15T10:00:00Z",
                    Map.of(
                            "requestId",
                            id,
                            "service",
                            service,
                            "candidateId",
                            "cand_1",
                            "status",
                            "EXPORTED",
                            "section",
                            Map.of())));
        mvc.perform(get("/api/v1/public/privacy-requests/" + id + "/export")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "receipt-app_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections['compliance-service']").exists());
        rig.clock.advance(java.time.Duration.ofDays(8));
        mvc.perform(get("/api/v1/public/privacy-requests/" + id + "/export")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "receipt-app_1"))
                .andExpect(status().isGone());
    }

    @Test
    void theInternalConsentLookupNeedsTheServiceSecret() throws Exception {
        mvc.perform(get("/api/v1/internal/consent/active")
                        .param("tenantId", "t1")
                        .header("X-Internal-Service-Secret", "bad"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/internal/consent/active")
                        .param("tenantId", "t1")
                        .param("purpose", "PROCESSING")
                        .header("X-Internal-Service-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("privacy-v1"))
                .andExpect(jsonPath("$.text").isNotEmpty());
    }

    @Test
    void theOverviewHasTheContractedFields() throws Exception {
        mvc.perform(get("/api/v1/compliance/overview").principal(as("t1", "COMPLIANCE_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditRecords30d").value(1))
                .andExpect(jsonPath("$.chainValid").value(true))
                .andExpect(jsonPath("$.openPrivacyRequests").value(0))
                .andExpect(jsonPath("$.activeLegalHolds").value(0))
                .andExpect(jsonPath("$.activeConsentVersion").value("privacy-v1"))
                .andExpect(jsonPath("$.recentAudit.length()").value(1))
                .andExpect(jsonPath("$.recentPrivacy.length()").value(0));
    }
}
