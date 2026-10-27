package com.openhire.jobservice.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.jobservice.service.JobApplicationService;
import com.openhire.jobservice.service.PublicCareerCatalog;
import com.openhire.platform.web.GlobalExceptionHandler;
import com.openhire.platform.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobProjectionControllerTest {
    @Test
    void unavailableCareerListingReturnsNotFoundWithoutAnErrorDispatch() throws Exception {
        var jobs = mock(JobApplicationService.class);
        var careers = mock(PublicCareerCatalog.class);
        when(careers.detail("tenant", "job"))
                .thenThrow(new ResourceNotFoundException("Career listing is not available"));
        var mvc = MockMvcBuilders.standaloneSetup(new JobProjectionController(jobs, careers))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/public/jobs/job").param("tenantId", "tenant")).andExpect(status().isNotFound());
    }
}
