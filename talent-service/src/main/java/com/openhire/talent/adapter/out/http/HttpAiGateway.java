package com.openhire.talent.adapter.out.http;

import com.openhire.support.Access;
import com.openhire.talent.application.port.TalentPorts.AiGateway;
import com.openhire.talent.application.port.TalentPorts.AiUnavailableException;
import com.openhire.talent.application.port.TalentPorts.ExplainRequest;
import com.openhire.talent.application.port.TalentPorts.Explanation;
import com.openhire.talent.application.port.TalentPorts.NlFilter;
import com.openhire.talent.domain.TalentTypes.Chunk;
import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.domain.TalentTypes.StructuredResume;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The ai-gateway internal API. Any failure (503, 402, timeout, malformed body) is reported as unavailable. */
@Component
class HttpAiGateway implements AiGateway {
    private final RestClient client;

    HttpAiGateway(
            Access access,
            @Value("${openhire.talent.gateway-url}") String url,
            @Value("${openhire.talent.gateway-timeout-ms:30000}") int timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeoutMs);
        client = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Internal-Service-Secret", access.secret())
                .requestFactory(factory)
                .build();
    }

    @Override
    public float[] embed(String tenantId, String text) {
        var response = call(tenantId, "embed", Map.of("inputs", List.of(text)));
        if (response.get("vectors") instanceof List<?> vectors
                && !vectors.isEmpty()
                && vectors.getFirst() instanceof List<?> first
                && !first.isEmpty()) {
            var vector = new float[first.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = ((Number) first.get(i)).floatValue();
            return vector;
        }
        throw new AiUnavailableException("The embedding response had no vector", null);
    }

    @Override
    public StructuredResume structureResume(String tenantId, String text) {
        var response = call(tenantId, "structure-resume", Map.of("text", text));
        var titles = new ArrayList<String>();
        if (response.get("roles") instanceof List<?> roles)
            for (var role : roles)
                if (role instanceof Map<?, ?> map && map.get("title") instanceof String title) titles.add(title);
        return new StructuredResume(
                response.get("headline") instanceof String headline ? headline : null,
                strings(response.get("skills")),
                titles,
                response.get("totalYearsExperience") instanceof Number years ? years.doubleValue() : null);
    }

    @Override
    public NlFilter nlFilter(String tenantId, String query) {
        var response = call(tenantId, "nl-filter", Map.of("query", query));
        var raw = response.get("filters") instanceof Map<?, ?> map ? map : Map.of();
        var filters = new Filters(
                strings(raw.get("locations")),
                strings(raw.get("skills")),
                raw.get("minYears") instanceof Number n ? n.intValue() : null,
                raw.get("lastInterviewedWithinDays") instanceof Number n ? n.intValue() : null,
                raw.get("openToRelocate") instanceof Boolean b ? b : null,
                raw.get("stage") instanceof String s && !s.isBlank() ? s : null);
        return new NlFilter(
                filters,
                response.get("semanticQuery") instanceof String semantic ? semantic : null,
                strings(response.get("chips")));
    }

    @Override
    public Explanation explainFit(String tenantId, ExplainRequest request) {
        var job = new LinkedHashMap<String, Object>();
        job.put("title", request.jobTitle());
        job.put("summary", request.jobSummary());
        job.put("criteria", request.criteria());
        var body = new LinkedHashMap<String, Object>();
        body.put("job", job);
        body.put("historical", chunks(request.historical()));
        body.put("current", chunks(request.current()));
        var response = call(tenantId, "explain-fit", body);
        return new Explanation(text(response.get("whyFitNow")), text(response.get("historicalEvidence")));
    }

    private Map<?, ?> call(String tenantId, String task, Object body) {
        try {
            var response = client.post()
                    .uri("/api/v1/internal/ai/" + task)
                    .header("X-Tenant-Id", tenantId)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) throw new AiUnavailableException("Empty response from the AI gateway", null);
            return response;
        } catch (RestClientException failure) {
            throw new AiUnavailableException("The AI gateway could not complete " + task, failure);
        }
    }

    private static List<Map<String, Object>> chunks(List<Chunk> chunks) {
        return chunks.stream()
                .map(chunk -> {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("chunkId", chunk.chunkId());
                    item.put("text", chunk.text());
                    return (Map<String, Object>) item;
                })
                .toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /** The gateway labels each statement by source; a list of statements is joined, a string is kept as is. */
    private static String text(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof List<?> list) return String.join(" ", strings(list));
        return null;
    }
}
