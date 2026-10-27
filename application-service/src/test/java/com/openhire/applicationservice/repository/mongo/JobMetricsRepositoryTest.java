package com.openhire.applicationservice.repository.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

class JobMetricsRepositoryTest {
    @Test
    void aggregatesAuthoritativeSubmissionsByTenantAndJobWithoutFetchingCandidates() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.aggregate(any(Aggregation.class), eq("submissions"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(
                        List.of(new Document("_id", "job-a")
                                .append("applicants", 12)
                                .append("shortlisted", 3)),
                        new Document()));
        var repository = new JobMetricsRepository(mongo);
        assertThat(repository.counts("tenant-a", List.of("job-a")))
                .containsExactly(new JobMetricsRepository.Counts("job-a", 12, 3));
        var pipeline = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(pipeline.capture(), eq("submissions"), eq(Document.class));
        assertThat(pipeline.getValue().toString())
                .contains("tenant-a", "tenantId", "job-a", "jobId", "SHORTLISTED", "$group", "$sum");
    }

    @Test
    void emptyBatchDoesNotScanTheCollection() {
        var mongo = mock(MongoTemplate.class);
        assertThat(new JobMetricsRepository(mongo).counts("tenant-a", List.of()))
                .isEmpty();
        verifyNoInteractions(mongo);
    }

    @Test
    void sourceCountsAreScopedToTenantAndJobAndKeepProviderSeparateFromIntakeType() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.aggregate(any(Aggregation.class), eq("submissions"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(
                        List.of(new Document("_id", "LINKEDIN").append("count", 3)), new Document()));
        assertThat(new JobMetricsRepository(mongo).sources("tenant-a", "job-a"))
                .containsExactly(new JobMetricsRepository.SourceCount("LINKEDIN", 3));
        var pipeline = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(pipeline.capture(), eq("submissions"), eq(Document.class));
        assertThat(pipeline.getValue().toString())
                .contains("tenant-a", "job-a", "$sourceProvider", "$source", "$ifNull");
    }
}
