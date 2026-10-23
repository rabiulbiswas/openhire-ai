package com.openhire.jobservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TemplateRequest(@NotBlank String key, @NotBlank String name, List<@NotBlank String> stageIds) {}
