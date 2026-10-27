package com.openhire.jobservice.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.jobservice.service.JobCategories;
import com.openhire.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class JobCategorySecurityTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        JobCategories categories() {
            return mock(JobCategories.class);
        }

        @Bean
        JobCategoryController controller(JobCategories categories) {
            return new JobCategoryController(categories);
        }
    }

    @Test
    void onlyOrganisationAdminCanMutateOrSeeInactiveCatalog() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(JobCategoryController.class);
            var service = context.getBean(JobCategories.class);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(TenantContext.class, "set", "tenant-a");
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "recruiter", "unused", AuthorityUtils.createAuthorityList("ROLE_RECRUITER")));
            var input = new JobCategoryController.Input("Finance", true, 0);
            assertThatThrownBy(() -> controller.create("recruiter", input)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.all(0, 20)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.delete("recruiter", "id", 1)).isInstanceOf(AccessDeniedException.class);
            controller.active(0, 100);
            verify(service).list("tenant-a", true, 0, 100);
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "admin", "unused", AuthorityUtils.createAuthorityList("ROLE_ORG_ADMIN")));
            controller.create("admin", input);
            verify(service).save("tenant-a", "admin", null, "Finance", true, 0);
        } finally {
            SecurityContextHolder.clearContext();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(TenantContext.class, "clear");
        }
    }
}
