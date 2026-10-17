package com.openhire.analytics.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.analytics.application.AnalyticsQueryService;
import com.openhire.analytics.application.AnalyticsViews.AnalyticsKpis;
import com.openhire.analytics.application.DeadLetterService;
import com.openhire.analytics.application.DeadLetterService.AlreadyReplayed;
import com.openhire.analytics.application.DeadLetterService.NotFound;
import com.openhire.analytics.application.DeadLetterService.RateLimited;
import com.openhire.analytics.application.DeadLetterViews.BatchResult;
import com.openhire.analytics.application.DeadLetterViews.DeadLetter;
import com.openhire.analytics.application.DeadLetterViews.ReplayResult;
import com.openhire.platform.web.GlobalExceptionHandler;
import com.openhire.support.Access;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeadLetterControllerTest {
    private final DeadLetterService deadLetters = mock(DeadLetterService.class);
    private final AnalyticsQueryService analytics = mock(AnalyticsQueryService.class);
    private final Viewers viewers = new Viewers(new Access("local", "demo-org", "secret"), "local");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new DeadLetterController(deadLetters, viewers), new AnalyticsController(analytics, viewers))
            .setControllerAdvice(new AnalyticsErrors(), new GlobalExceptionHandler())
            .build();

    @Test
    void listsDeadLettersWithoutAbsentOptionalFields() throws Exception {
        when(deadLetters.list(any(), eq(50)))
                .thenReturn(List.of(new DeadLetter(
                        "t.DLT:0:1", "t", "g", "e", "t1", "boom", null, "2026-10-15T10:00:00Z", null, null, "{}")));

        mvc.perform(get("/api/v1/admin/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t.DLT:0:1"))
                .andExpect(jsonPath("$[0].originalTopic").value("t"))
                .andExpect(jsonPath("$[0].payloadPreview").value("{}"))
                .andExpect(jsonPath("$[0].attempts").doesNotExist())
                .andExpect(jsonPath("$[0].replayedAt").doesNotExist());
    }

    @Test
    void replayReturnsTheResultAndPassesForceAndReason() throws Exception {
        when(deadLetters.replay(any(), eq("t.DLT:0:1"), eq("fixed upstream"), eq(true)))
                .thenReturn(new ReplayResult("t.DLT:0:1", "2026-10-15T10:00:00Z", "local-recruiter", 2));

        mvc.perform(post("/api/v1/admin/dlq/t.DLT:0:1/replay?force=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fixed upstream\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayCount").value(2));
    }

    @Test
    void aSecondReplayIs409AndAnUnknownMessageIs404() throws Exception {
        when(deadLetters.replay(any(), eq("t.DLT:0:1"), any(), anyBoolean()))
                .thenThrow(new AlreadyReplayed("t.DLT:0:1"));
        when(deadLetters.detail(any(), eq("t.DLT:0:9"))).thenThrow(new NotFound("Dead letter not found"));

        mvc.perform(post("/api/v1/admin/dlq/t.DLT:0:1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again please\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/admin/dlq/t.DLT:0:9")).andExpect(status().isNotFound());
    }

    @Test
    void exceedingTheReplayRateIs429WithRetryAfter() throws Exception {
        when(deadLetters.replay(any(), any(), any(), anyBoolean())).thenThrow(new RateLimited(42));

        mvc.perform(post("/api/v1/admin/dlq/t.DLT:0:1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too many\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"));
    }

    @Test
    void anInvalidReasonIs400() throws Exception {
        when(deadLetters.replay(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("reason is required (5 to 500 characters)"));

        mvc.perform(post("/api/v1/admin/dlq/t.DLT:0:1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchReplayPassesItsOptionsAndDryRunDefaultsToFalse() throws Exception {
        when(deadLetters.replayBatch(any(), eq("clear the backlog"), eq("offer.events.v1"), eq(10), eq(true)))
                .thenReturn(new BatchResult(true, 4, 0));

        mvc.perform(
                        post("/api/v1/admin/dlq/replay")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"clear the backlog\",\"topic\":\"offer.events.v1\",\"limit\":10,\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.matched").value(4))
                .andExpect(jsonPath("$.replayed").value(0));
        mvc.perform(post("/api/v1/admin/dlq/replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"clear the backlog\"}"));
        verify(deadLetters).replayBatch(any(), eq("clear the backlog"), eq(null), eq(null), eq(false));
    }

    @Test
    void analyticsKpisAreServedForTheCallersTenantWithTheContractFields() throws Exception {
        when(analytics.kpis("demo-org", "90d", "j1"))
                .thenReturn(new AnalyticsKpis(
                        10.0, "+2.1 days", 60.0, "+10 pts", 60.0, "-15 pts", 3.0, "no change", "INR"));

        mvc.perform(get("/api/v1/analytics/kpis?range=90d&jobId=j1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medianTimeToHireDays").value(10.0))
                .andExpect(jsonPath("$.medianTimeToHireDelta").value("+2.1 days"))
                .andExpect(jsonPath("$.offerAcceptanceDelta").value("+10 pts"))
                .andExpect(jsonPath("$.aiShortlistAgreementDelta").value("-15 pts"))
                .andExpect(jsonPath("$.aiCostPerScreenedApplicant").value(3.0))
                .andExpect(jsonPath("$.aiCostDelta").value("no change"))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void anUnsupportedRangeIs400() throws Exception {
        when(analytics.funnel(any(), any(), eq("7d")))
                .thenThrow(new IllegalArgumentException("range must be 30d, 90d or 1y"));

        mvc.perform(get("/api/v1/analytics/funnel?range=7d")).andExpect(status().isBadRequest());
    }
}
