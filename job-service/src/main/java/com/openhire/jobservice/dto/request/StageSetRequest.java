package com.openhire.jobservice.dto.request;

import com.openhire.jobservice.configuration.HiringConfiguration.Stage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record StageSetRequest(@NotBlank String key, @NotBlank String name, List<@Valid Stage> stages) {}
