package com.openhire.jobservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AiJobDraftRequest(
        @NotBlank String title, String team, String level, List<String> mustHaveSkills, String tone) {

    public AiJobDraftRequest normalized() {
        return new AiJobDraftRequest(
                title,
                team == null || team.isBlank() ? "the hiring team" : team,
                level == null ? "" : level,
                mustHaveSkills == null ? List.of() : List.copyOf(mustHaveSkills),
                tone == null || tone.isBlank() ? "clear, inclusive and direct" : tone);
    }
}
