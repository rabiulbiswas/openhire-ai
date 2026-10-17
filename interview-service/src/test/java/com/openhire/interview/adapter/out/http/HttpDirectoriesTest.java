package com.openhire.interview.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpDirectoriesTest {
    @Test
    @SuppressWarnings("unchecked")
    void readsThePlanAndRubricFromJsonDeserialisedMaps() {
        var json =
                """
                {"id":"job-1","title":"Backend Engineer","currentVersion":{"version":2,
                 "interviewPlan":[{"id":"p1","name":"Technical","durationMin":60,"interviewerNames":["Asha"]},
                                  {"id":"p2","name":"System design","durationMin":90,"interviewerNames":[]}],
                 "rubric":[{"id":"c1","label":"Problem solving","weight":60}]}}
                """;
        Map<String, Object> job = new ObjectMapper().readValue(json, Map.class);

        var plan = HttpDirectories.planFrom("job-1", job);

        assertThat(plan.title()).isEqualTo("Backend Engineer");
        assertThat(plan.rounds())
                .extracting(round -> round.name() + "/" + round.durationMin() + "/" + round.interviewerNames())
                .containsExactly("Technical/60/[Asha]", "System design/90/[]");
        assertThat(plan.competencies()).singleElement().satisfies(c -> assertThat(c.label())
                .isEqualTo("Problem solving"));
    }
}
