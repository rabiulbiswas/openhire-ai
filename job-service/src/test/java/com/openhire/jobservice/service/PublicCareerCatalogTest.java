package com.openhire.jobservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.openhire.platform.web.ResourceNotFoundException;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

class PublicCareerCatalogTest {
    @Test
    void boundsPaginationAndRejectsOversizedFiltersBeforeDatabaseWork() {
        var mongo = mock(MongoTemplate.class);
        var catalog = new PublicCareerCatalog(mongo);
        for (int page : new int[] {0, -1, 100001})
            assertThatThrownBy(() -> catalog.list("tenant", page, 10, "", "", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        for (int size : new int[] {0, -1, 51})
            assertThatThrownBy(() -> catalog.list("tenant", 1, size, "", "", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.list("tenant", 1, 10, "a".repeat(201), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.list("", 1, 10, "", "", "")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mongo);
    }

    @Test
    void pagesInMongoAndUsesOnlyCurrentApprovedSnapshotAndPublicFields() {
        var mongo = mock(MongoTemplate.class);
        var facet = new Document("data", List.of(new Document("id", "job")))
                .append("total", List.of(new Document("value", 21)))
                .append("locations", List.of(new Document("_id", "Pune")))
                .append("categories", List.of(new Document("_id", "software").append("name", "IT / Software")));
        when(mongo.aggregate(any(Aggregation.class), eq("jobs"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(facet), new Document()));
        var result = new PublicCareerCatalog(mongo).list("tenant-a", 2, 10, "a.*", "Pune", "software");
        assertThat(result.get("total")).isEqualTo(21L);
        assertThat(result.get("totalPages")).isEqualTo(3L);
        assertThat(result.get("locations")).isEqualTo(List.of("Pune"));
        var capture = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(capture.capture(), eq("jobs"), eq(Document.class));
        var pipeline = capture.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
        assertThat(pipeline.getFirst().toJson()).contains("tenant-a", "OPEN", "approvedVersion", "currentVersion");
        assertThat(pipeline.toString())
                .contains("\\Qa.*\\E", "$skip=10", "$limit=10", "careerVersion.classification.categoryId");
        assertThat(pipeline.toString()).doesNotContain("requiredAnswer", "hiringReason", "hiringTeam", "rubric");
    }

    @Test
    void detailIsTenantScopedAndDoesNotExposeRequiredKnockoutAnswers() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.aggregate(any(Aggregation.class), eq("jobs"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(), new Document()));
        assertThatThrownBy(() -> new PublicCareerCatalog(mongo).detail("tenant-a", "missing"))
                .isInstanceOf(ResourceNotFoundException.class);
        var capture = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(capture.capture(), eq("jobs"), eq(Document.class));
        var pipeline =
                capture.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
        assertThat(pipeline)
                .contains(
                        "tenant-a",
                        "missing",
                        "approvedVersion",
                        "questions",
                        "careerVersion.qualification",
                        "careerVersion.goodToHaveSkills");
        assertThat(pipeline).doesNotContain("requiredAnswer", "rubric", "hiringReason", "hiringTeam");
    }
}
