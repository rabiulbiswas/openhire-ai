package com.openhire.resumeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

class TransientFailureTest {
    @Test
    void dependencyOutagesAreRetriedButContentProblemsAreNot() {
        assertThat(ResumePipeline.transientFailure(new ConnectException("Connection refused")))
                .isTrue();
        assertThat(ResumePipeline.transientFailure(new SocketTimeoutException("read timed out")))
                .isTrue();
        assertThat(ResumePipeline.transientFailure(new ResumePipeline.Unavailable("SCANNER_UNAVAILABLE")))
                .isTrue();
        assertThat(ResumePipeline.transientFailure(new ResumePipeline.Unavailable("EXTRACTION_UNAVAILABLE")))
                .isTrue();

        assertThat(ResumePipeline.transientFailure(new IOException("NO_EXTRACTABLE_TEXT")))
                .isFalse();
        assertThat(ResumePipeline.transientFailure(new IOException("EXTRACTION_FAILED")))
                .isFalse();
        assertThat(ResumePipeline.transientFailure(new IOException("TEXT_LIMIT_EXCEEDED")))
                .isFalse();
    }

    @Test
    void theClaimQueryCombinesStatusAttemptsAndBackOff() {
        var query = org.springframework.data.mongodb.core.query.Query.query(
                        ResumePipeline.claimable("2026-10-09T10:00:00Z"))
                .getQueryObject()
                .toJson();

        assertThat(query)
                .contains("\"$and\"")
                .contains("PENDING")
                .contains("nextAttempt")
                .contains("attempts");
    }
}
