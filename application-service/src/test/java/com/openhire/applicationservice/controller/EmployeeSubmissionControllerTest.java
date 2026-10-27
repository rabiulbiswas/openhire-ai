package com.openhire.applicationservice.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.dto.PartnerPage;
import com.openhire.applicationservice.repository.mongo.PartnerRepository;
import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import com.openhire.support.Records;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class EmployeeSubmissionControllerTest {
    private final PartnerRepository repository = mock(PartnerRepository.class);
    private final Access access = new Access("oidc", "", "secret");
    private final EmployeeSubmissionController controller = new EmployeeSubmissionController(repository, access);

    private JwtAuthenticationToken auth(String role) {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("test")
                        .header("alg", "RS256")
                        .subject("employee")
                        .claim("tenant_id", "tenant-a")
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void ownSubmissionsOnlyExposePublicFieldsAndPreservePagination() {
        var raw = new Document("_id", "s")
                .append("name", "Demo")
                .append("stage", "AI_ASSESSED")
                .append("jobTitle", "Engineer")
                .append("submittedAt", "2026-10-25T00:00:00Z")
                .append("score", 99)
                .append("notes", "private");
        when(repository.employeeSubmissions("tenant-a", "employee", "cursor", "", 10))
                .thenReturn(new PartnerPage<>(List.of(raw), "next", true, null, false, 10));
        var page = controller.submissions(auth("EMPLOYEE"), "cursor", "", 10);
        assertThat(page.data().getFirst())
                .containsOnlyKeys("id", "name", "jobTitle", "stage", "submittedAt")
                .containsEntry("stage", "UNDER_REVIEW");
        assertThat(page.nextCursor()).isEqualTo("next");
    }

    @Test
    void agencyCannotReadEmployeeSubmissions() {
        assertThatThrownBy(() -> controller.submissions(auth("AGENCY_RECRUITER"), "", "", 25))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void candidateContactDetailsRequireTheMatchingReceipt() {
        var service = mock(SubmissionService.class);
        when(service.get("tenant-a", "s"))
                .thenReturn(new Document("receiptHash", Records.hash("receipt-secret"))
                        .append("name", "Demo Candidate")
                        .append("email", "demo@example.test")
                        .append("phone", "+12025550100")
                        .append("jobTitle", "Engineer")
                        .append("stage", "APPLIED")
                        .append("submittedAt", "2026-10-25T00:00:00Z"));
        var intake = new SubmissionController(service, access);
        assertThatThrownBy(() -> intake.status("s", "tenant-a", "wrong")).isInstanceOf(ResponseStatusException.class);
        var result = (java.util.Map<?, ?>) intake.status("s", "tenant-a", "receipt-secret");
        assertThat(result.containsKey("name")).isTrue();
        assertThat(result.containsKey("receiptHash")).isFalse();
    }
}
