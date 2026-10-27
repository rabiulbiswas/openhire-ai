package com.openhire.applicationservice.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.dto.PartnerPage;
import com.openhire.applicationservice.repository.mongo.PartnerRepository;
import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class PartnerControllerTest {
    private final PartnerRepository repository = mock(PartnerRepository.class);
    private final SubmissionService service = mock(SubmissionService.class);
    private final PartnerController controller =
            new PartnerController(repository, service, new Access("oidc", "", "secret"));

    private JwtAuthenticationToken auth(String role) {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("test")
                        .header("alg", "RS256")
                        .subject("agency-user")
                        .claim("tenant_id", "tenant-a")
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void agencyCannotSubmitToUnassignedJobOrAssignAnInternalOwner() throws Exception {
        var mongo = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        when(service.store()).thenReturn(mongo);
        var intake = new SubmissionController(service, new Access("oidc", "", "secret"));
        var input = new Document("source", "AGENCY").append("jobId", "job-a").append("owner", "someone-else");
        assertThatThrownBy(() -> intake.json(auth("AGENCY_RECRUITER"), "key", input))
                .isInstanceOf(ResponseStatusException.class);
        when(mongo.exists(any(org.springframework.data.mongodb.core.query.Query.class), eq("agency_assignments")))
                .thenReturn(true);
        when(service.submit(eq("tenant-a"), eq("agency-user"), eq("key"), any(Document.class), isNull()))
                .thenReturn(new Document("_id", "submission"));
        intake.json(auth("AGENCY_RECRUITER"), "key", input);
        assertThat(input.getString("owner")).isEmpty();
        var scope = org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongo, times(2)).exists(scope.capture(), eq("agency_assignments"));
        assertThat(scope.getValue().getQueryObject().toString())
                .contains("tenant-a", "agency-user", "job-a", "active=true");
    }

    @Test
    void readsOnlyVerifiedTenantAndActorAndReturnsPublicFields() {
        var raw = new Document("_id", "submission")
                .append("name", "Ada")
                .append("jobTitle", "Engineer")
                .append("stage", "AI_ASSESSED")
                .append("submittedAt", "2026-10-25T00:00:00Z")
                .append("score", 99)
                .append("assessment", "private");
        when(repository.submissions("tenant-a", "agency-user", "cursor", "", 10))
                .thenReturn(new PartnerPage<>(List.of(raw), "next", true, null, false, 10));
        var page = controller.submissions(auth("AGENCY_RECRUITER"), "cursor", "", 10);
        assertThat(page.data().getFirst())
                .containsOnlyKeys("id", "name", "jobTitle", "stage", "submittedAt")
                .containsEntry("stage", "UNDER_REVIEW");
        assertThat(page.nextCursor()).isEqualTo("next");
    }

    @Test
    void employeesCannotUseAgencyEndpointsAndUnassignedJobIsDenied() {
        assertThatThrownBy(() -> controller.jobs(auth("EMPLOYEE"), "", "", 25))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.submissions(auth("EMPLOYEE"), "", "", 25))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.job(auth("AGENCY_RECRUITER"), "unassigned"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    void assignedJobDetailsDoNotAllowClientSuppliedActorOrTenant() {
        when(repository.assigned("tenant-a", "agency-user", "job-a")).thenReturn(true);
        when(service.job("tenant-a", "job-a"))
                .thenReturn(new Document("title", "Engineer")
                        .append("location", "Kolkata")
                        .append("status", "OPEN"));
        assertThat(controller.job(auth("AGENCY_RECRUITER"), "job-a")).containsEntry("status", "OPEN");
        verify(service).job("tenant-a", "job-a");
    }
}
