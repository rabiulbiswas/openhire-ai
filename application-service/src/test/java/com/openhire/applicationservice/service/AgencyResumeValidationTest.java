package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AgencyResumeValidationTest {
    @Test
    void checksRequiredSizeExtensionAndActualFileSignature() throws Exception {
        SubmissionService.validateAgencyResume(
                new MockMultipartFile("resume", "resume.pdf", "application/pdf", "%PDF-1.7 demo".getBytes()));
        assertThatThrownBy(() -> SubmissionService.validateAgencyResume(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubmissionService.validateAgencyResume(
                        new MockMultipartFile("resume", "resume.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubmissionService.validateAgencyResume(
                        new MockMultipartFile("resume", "resume.pdf", "application/pdf", "not a pdf".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubmissionService.validateAgencyResume(
                        new MockMultipartFile("resume", "resume.exe", "application/pdf", "%PDF-1.7 demo".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubmissionService.validateAgencyResume(new MockMultipartFile(
                        "resume", "large.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
