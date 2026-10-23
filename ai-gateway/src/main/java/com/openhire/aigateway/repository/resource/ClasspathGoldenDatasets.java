package com.openhire.aigateway.repository.resource;

import com.openhire.aigateway.domain.Golden;
import com.openhire.aigateway.domain.Json;
import com.openhire.aigateway.service.port.AiPorts.GoldenDatasets;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads the versioned golden dataset shipped in the service resources. */
@Component
class ClasspathGoldenDatasets implements GoldenDatasets {
    private static final String PATH = "eval/golden-v1.json";

    @Override
    public Golden.Dataset load() {
        try (var in = new ClassPathResource(PATH).getInputStream()) {
            return Json.convert(
                    Json.read(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)),
                    Golden.Dataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("Golden dataset is missing", e);
        }
    }
}
