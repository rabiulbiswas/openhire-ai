package com.openhire.offer.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpDirectoriesTest {
    @Test
    void theBandIsReadFromPlainJsonMaps() {
        // Nested JSON objects arrive as LinkedHashMap, not BSON documents.
        var band = new LinkedHashMap<String, Object>();
        band.put("currency", "INR");
        band.put("min", 20);
        band.put("max", 30.5);
        var job = new LinkedHashMap<String, Object>();
        job.put("title", "Senior Engineer");
        job.put("location", "Bengaluru");
        job.put("hiringManager", "");
        job.put("salaryBand", band);

        var info = HttpDirectories.jobInfo("job-1", job);

        assertThat(info.title()).isEqualTo("Senior Engineer");
        assertThat(info.band().currency()).isEqualTo("INR");
        assertThat(info.band().min()).isEqualByComparingTo("20");
        assertThat(info.band().max()).isEqualByComparingTo("30.5");
        assertThat(info.band().midpoint()).isEqualByComparingTo("25.25");
    }

    @Test
    void aJobWithoutABandHasNoBandRatherThanAnError() {
        var info = HttpDirectories.jobInfo("job-1", Map.of("title", "Engineer"));
        assertThat(info.band()).isNull();
        assertThat(info.location()).isEmpty();
    }
}
