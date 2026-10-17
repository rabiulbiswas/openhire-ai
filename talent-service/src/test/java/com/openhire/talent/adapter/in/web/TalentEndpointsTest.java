package com.openhire.talent.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.support.Access;
import com.openhire.talent.application.ConsentService;
import com.openhire.talent.application.InvitationService;
import com.openhire.talent.application.InvitationService.AccessDenied;
import com.openhire.talent.application.RediscoveryService;
import com.openhire.talent.application.SearchService;
import com.openhire.talent.application.TalentViews.ConsentView;
import com.openhire.talent.application.TalentViews.SearchResult;
import com.openhire.talent.application.port.TalentPorts.ReceiptRejected;
import com.openhire.talent.domain.TalentTypes.Filters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TalentEndpointsTest {
    private final ConsentService consent = mock(ConsentService.class);
    private final RediscoveryService rediscovery = mock(RediscoveryService.class);
    private final SearchService search = mock(SearchService.class);
    private final InvitationService invitations = mock(InvitationService.class);
    private final Access access = new Access("local", "demo-org", "test-secret");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new PublicConsentController(consent),
                    new TalentController(rediscovery, search, invitations, new Viewers(access)))
            .setControllerAdvice(new TalentErrors(), new com.openhire.platform.web.GlobalExceptionHandler())
            .build();
    private final TestingAuthenticationToken recruiter =
            new TestingAuthenticationToken("recruiter-1", "n/a", "ROLE_RECRUITER");

    @Test
    void anInvalidReceiptIsForbiddenOnBothConsentCalls() throws Exception {
        when(consent.set(eq("t1"), eq("app-1"), eq("bad"), eq(true))).thenThrow(new ReceiptRejected());
        when(consent.current(eq("t1"), eq("app-1"), eq("bad"))).thenThrow(new ReceiptRejected());

        mvc.perform(put("/api/v1/public/applications/app-1/talent-consent")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/public/applications/app-1/talent-consent")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "bad"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aValidReceiptSetsAndReadsConsent() throws Exception {
        when(consent.set("t1", "app-1", "ok", false)).thenReturn(new ConsentView(false, "2026-10-15T00:00:00Z"));
        when(consent.current("t1", "app-1", "ok")).thenReturn(new ConsentView(true, null));

        mvc.perform(put("/api/v1/public/applications/app-1/talent-consent")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(false))
                .andExpect(jsonPath("$.consentUpdatedAt").value("2026-10-15T00:00:00Z"));
        mvc.perform(get("/api/v1/public/applications/app-1/talent-consent")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "ok"))
                .andExpect(jsonPath("$.granted").value(true));
        mvc.perform(put("/api/v1/public/applications/app-1/talent-consent")
                        .param("tenantId", "t1")
                        .header("X-Application-Receipt", "ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchResultsUseTheUiShapeWithoutNullFields() throws Exception {
        when(search.search(eq("demo-org"), any(), eq("kafka")))
                .thenReturn(new SearchResult(
                        List.of("Skill: Kafka"),
                        new Filters(List.of(), List.of("Kafka"), null, null, null, null),
                        "kafka",
                        List.of(),
                        null,
                        null,
                        "EMBEDDING"));

        mvc.perform(post("/api/v1/talent/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"skills\":[\"Kafka\"]},\"semanticQuery\":\"kafka\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters[0]").value("Skill: Kafka"))
                .andExpect(jsonPath("$.filterSpec.skills[0]").value("Kafka"))
                .andExpect(jsonPath("$.filterSpec.minYears").doesNotExist())
                .andExpect(jsonPath("$.rediscoveryForJobTitle").doesNotExist())
                .andExpect(jsonPath("$.candidates").isArray());
    }

    @Test
    void inviteIsNoContentForARecruiterAndForbiddenWhenTheServiceDeniesIt() throws Exception {
        mvc.perform(post("/api/v1/talent/cand-1/invite")
                        .principal(recruiter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"job-1\"}"))
                .andExpect(status().isNoContent());
        verify(invitations).invite(eq("demo-org"), any(), eq("cand-1"), eq("job-1"));

        doThrow(new AccessDenied("Recruiter access is required"))
                .when(invitations)
                .invite(any(), any(), eq("cand-2"), any());
        mvc.perform(post("/api/v1/talent/cand-2/invite").principal(recruiter)).andExpect(status().isForbidden());
    }

    @Test
    void outsideLocalModeNonStaffAreRefusedBeforeAnythingRuns() throws Exception {
        var strict = new Access("oidc", "demo-org", "test-secret");
        var locked = MockMvcBuilders.standaloneSetup(
                        new TalentController(rediscovery, search, invitations, new Viewers(strict)))
                .setControllerAdvice(new TalentErrors())
                .build();
        var manager = new TestingAuthenticationToken("hm-1", "n/a", "ROLE_HIRING_MANAGER");

        locked.perform(get("/api/v1/talent/pool-health").principal(manager)).andExpect(status().isForbidden());
        locked.perform(post("/api/v1/talent/c/invite").principal(manager)).andExpect(status().isForbidden());
    }
}
