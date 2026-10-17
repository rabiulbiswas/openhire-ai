package com.openhire.talent.domain;

import java.time.Instant;
import java.util.List;

public final class TalentTypes {
    private TalentTypes() {}

    /** The candidate's choice about being considered for future roles. Only GRANTED makes a profile searchable. */
    public enum Consent {
        UNKNOWN,
        GRANTED,
        REVOKED
    }

    public enum ProfileState {
        ACTIVE,
        EXPIRED
    }

    public record ApplicationRef(
            String applicationId,
            String jobId,
            String jobTitle,
            String stage,
            Instant appliedAt,
            Integer assessmentScore) {
        public ApplicationRef withScore(Integer score) {
            return new ApplicationRef(applicationId, jobId, jobTitle, stage, appliedAt, score);
        }
    }

    /** One move of an application between stages, kept for the history signal and for explanations. */
    public record StageStep(String eventId, String applicationId, String jobId, String from, String to, Instant at) {}

    /** A short statement from a past application (assessment strengths and quoted evidence). Always HISTORICAL. */
    public record Evidence(String applicationId, String jobTitle, String text) {}

    /** An excerpt of the current resume; the CURRENT evidence shown to the explanation model. */
    public record Chunk(String chunkId, String text) {}

    /** The editable structured filter behind the search chips. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Filters(
            List<String> locations,
            List<String> skills,
            Integer minYears,
            Integer lastInterviewedWithinDays,
            Boolean openToRelocate,
            String stage) {
        public Filters {
            locations = locations == null ? List.of() : List.copyOf(locations);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        public static Filters none() {
            return new Filters(List.of(), List.of(), null, null, null, null);
        }
    }

    public record StructuredResume(
            String headline, List<String> skills, List<String> titles, Double totalYearsExperience) {
        public StructuredResume {
            skills = skills == null ? List.of() : List.copyOf(skills);
            titles = titles == null ? List.of() : List.copyOf(titles);
        }
    }

    /** One scored candidate before explanations; the components are kept for transparency in the run record. */
    public record Scored(
            String candidateId, double score, double semantic, double ruleCoverage, double recency, double history) {}
}
