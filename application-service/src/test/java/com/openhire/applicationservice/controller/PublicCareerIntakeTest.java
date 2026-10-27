package com.openhire.applicationservice.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.platform.web.GlobalExceptionHandler;
import com.openhire.support.Access;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicCareerIntakeTest {
    @Test
    void refusesMultipartTenantOverrideBeforeCreatingAnApplication() throws Exception {
        var submissions = mock(SubmissionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SubmissionController(submissions, mock(Access.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(multipart("/api/v1/public/applications")
                .file(new MockMultipartFile("resume", "resume.pdf", "application/pdf", "%PDF-1.7 demo".getBytes()))
                .param("tenantId", "other-tenant").header("Idempotency-Key", "key")
                .header("X-Public-Tenant-Id", "host-tenant"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(submissions);
    }
}
