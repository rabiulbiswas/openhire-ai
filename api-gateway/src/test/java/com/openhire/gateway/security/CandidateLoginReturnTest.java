package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CandidateLoginReturnTest {
    @Test void acceptsOnlyKnownCandidatePagesNotExternalOrProtocolRelativeRedirects() {
        assertThat(CandidateLoginReturn.safe("/careers/jobs/job-1/apply")).isEqualTo("/careers/jobs/job-1/apply");
        assertThat(CandidateLoginReturn.safe(null)).isEqualTo("/candidate/portal");
        for (String path : new String[] {"https://attacker.test/", "//attacker.test", "/jobs", "/careers/jobs/%2f%2fattacker", "/careers/profile?next=evil", "/\\attacker"})
            assertThatThrownBy(() -> CandidateLoginReturn.safe(path)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
