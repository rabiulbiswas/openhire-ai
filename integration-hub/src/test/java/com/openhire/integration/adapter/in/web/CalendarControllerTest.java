package com.openhire.integration.adapter.in.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.openhire.integration.application.CalendarConnections;
import com.openhire.support.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CalendarControllerTest {
    final CalendarConnections calendars = mock(CalendarConnections.class);
    final Access access = mock(Access.class);
    final Authentication auth = mock(Authentication.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CalendarController(calendars, access))
                .build();
        when(access.tenant(auth)).thenReturn("tenant");
        when(access.actor(auth)).thenReturn("admin");
    }

    @Test
    void rejectsNonAdminBeforeCallingProvider() throws Exception {
        mvc.perform(post("/api/v1/connectors/c/authorize").principal(auth)).andExpect(status().isForbidden());
        verifyNoInteractions(calendars);
    }

    @Test
    void authorizeUsesAuthenticatedTenantAndActor() throws Exception {
        when(access.role(auth, "ORG_ADMIN")).thenReturn(true);
        when(calendars.authorize("tenant", "admin", "c")).thenReturn("https://accounts.google.com/auth");
        mvc.perform(post("/api/v1/connectors/c/authorize").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("https://accounts.google.com/auth"));
    }

    @Test
    void callbackDoesNotAcceptTenantFromBody() throws Exception {
        when(access.role(auth, "ORG_ADMIN")).thenReturn(true);
        mvc.perform(post("/api/v1/connectors/oauth/callback")
                        .principal(auth)
                        .contentType("application/json")
                        .content("{\"state\":\"s\",\"code\":\"c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        verify(calendars).complete("tenant", "admin", "s", "c");
    }

    @Test
    void disconnectIsLocalAndExplicit() throws Exception {
        when(access.role(auth, "ORG_ADMIN")).thenReturn(true);
        mvc.perform(delete("/api/v1/connectors/c/connection").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice")
                        .value("Local tokens deleted. Revoke provider consent separately if required."));
        verify(calendars).disconnect("tenant", "admin", "c");
    }
}
