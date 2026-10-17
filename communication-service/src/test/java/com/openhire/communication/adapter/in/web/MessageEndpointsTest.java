package com.openhire.communication.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.communication.application.CommunicationService;
import com.openhire.communication.application.CommunicationService.TemplateSend;
import com.openhire.communication.application.CommunicationService.Unavailable;
import com.openhire.support.Access;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MessageEndpointsTest {
    private final CommunicationService service = mock(CommunicationService.class);
    private final Access access = new Access("local", "demo-org", "test-secret");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new InternalMessageController(service, access),
                    new ConversationController(service, new StaffViewers(access)))
            .setControllerAdvice(new CommunicationErrors())
            .build();

    private static final String BODY =
            "{\"tenantId\":\"t1\",\"applicationId\":\"app-1\",\"templateKey\":\"talent-reengagement\","
                    + "\"variables\":{\"job.title\":\"Staff Engineer\"},\"actor\":\"recruiter-1\"}";

    @Test
    void theInternalTemplateEndpointNeedsTheServiceSecret() throws Exception {
        mvc.perform(post("/api/v1/internal/messages/template")
                        .header("X-Internal-Service-Secret", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theInternalTemplateEndpointSendsAndReportsSuppression() throws Exception {
        when(service.sendTemplate(
                        eq("t1"),
                        eq("app-1"),
                        eq("talent-reengagement"),
                        eq(Map.of("job.title", "Staff Engineer")),
                        eq("recruiter-1")))
                .thenReturn(new TemplateSend(false, "MARKETING_OPT_OUT"));

        mvc.perform(post("/api/v1/internal/messages/template")
                        .header("X-Internal-Service-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.reason").value("MARKETING_OPT_OUT"));
    }

    @Test
    void aiDraftReturnsTheTextAndAnOutageIsAConflict() throws Exception {
        when(service.aiDraft(any(), eq("conv-1"))).thenReturn("Tuesday works.");
        when(service.aiDraft(any(), eq("conv-2"))).thenThrow(new Unavailable("AI drafting is unavailable right now"));

        mvc.perform(post("/api/v1/conversations/conv-1/ai-draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Tuesday works."));
        mvc.perform(post("/api/v1/conversations/conv-2/ai-draft"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("AI drafting is unavailable right now"));
    }
}
