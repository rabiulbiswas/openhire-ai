package com.openhire.jobservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiJobDraftRequest(
        @NotBlank String title,
        String team,
        String level,
        List<String> mustHaveSkills,
        String tone,
        @Size(max = 1000) String qualification,
        @Size(max = 15) List<@NotBlank @Size(max = 60) String> goodToHaveSkills,
        String location,
        String workMode) {

    public AiJobDraftRequest(String title, String team, String level, List<String> mustHaveSkills, String tone) {
        this(title, team, level, mustHaveSkills, tone, null, List.of(), null, null);
    }

    public AiJobDraftRequest normalized() {
        return new AiJobDraftRequest(
                title,
                team == null || team.isBlank() ? "the hiring team" : team,
                level == null ? "" : level,
                mustHaveSkills == null ? List.of() : List.copyOf(mustHaveSkills),
                tone == null || tone.isBlank() ? "clear, inclusive and direct" : tone,
                qualification,
                com.openhire.jobservice.domain.Job.normalizeSkills(goodToHaveSkills),
                location,
                workMode);
    }
}
