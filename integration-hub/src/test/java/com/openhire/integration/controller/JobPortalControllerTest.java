package com.openhire.integration.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.openhire.integration.service.JobPortals;
import com.openhire.support.Access;
import org.junit.jupiter.api.*;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobPortalControllerTest {
    final JobPortals portals = mock(JobPortals.class);
    final Access access = mock(Access.class);
    final Authentication auth = mock(Authentication.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new JobPortalController(portals, access))
                .build();
        when(access.tenant(auth)).thenReturn("tenant");
        when(access.actor(auth)).thenReturn("recruiter");
    }

    @Test
    void recruiterCannotConfigureConnections() throws Exception {
        when(access.role(auth, "RECRUITER")).thenReturn(true);
        mvc.perform(put("/api/v1/job-portals/LINKEDIN")
                        .principal(auth)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"secretRef\":\"secret://tenant/linkedin\",\"revision\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(portals);
    }

    @Test
    void recruiterUsesVerifiedTenantAndActorNotRequestFields() throws Exception {
        when(access.role(auth, "RECRUITER")).thenReturn(true);
        mvc.perform(put("/api/v1/job-portals/jobs/job/LINKEDIN")
                        .principal(auth)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"revision\":2,\"tenant\":\"other\",\"actor\":\"other\"}"))
                .andExpect(status().isOk());
        verify(portals).desire("tenant", "recruiter", "job", "LINKEDIN", true, 2);
    }

    @Test
    void hiringManagerCannotPublishExternally() throws Exception {
        when(access.role(auth, "HIRING_MANAGER")).thenReturn(true);
        mvc.perform(put("/api/v1/job-portals/jobs/job/LINKEDIN")
                        .principal(auth)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"revision\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(portals);
    }
}
