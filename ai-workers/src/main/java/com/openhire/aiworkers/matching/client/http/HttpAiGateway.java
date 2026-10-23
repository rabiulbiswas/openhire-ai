package com.openhire.aiworkers.matching.client.http;

import com.openhire.aiworkers.matching.domain.Judgement;
import com.openhire.aiworkers.matching.domain.MatchingRequest.Criterion;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AiGateway;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.ChunkText;
import com.openhire.aiworkers.shared.client.http.InternalClient;
import com.openhire.support.Access;
import com.openhire.support.Records;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpAiGateway implements AiGateway {
    private final InternalClient gateway;

    HttpAiGateway(Access access, @Value("${openhire.ai.gateway-url}") String gatewayUrl) {
        gateway = new InternalClient(gatewayUrl, access.secret(), 120_000);
    }

    @Override
    public List<double[]> embed(String tenantId, List<String> inputs) {
        var response = gateway.post(tenantId, "/api/v1/internal/ai/embed", Map.of("inputs", inputs));
        if (!(response.get("vectors") instanceof List<?> rows)) throw new IllegalStateException("No vectors returned");
        return rows.stream().map(HttpAiGateway::vector).toList();
    }

    @Override
    public Judgement judge(String tenantId, Criterion criterion, List<ChunkText> chunks, String rubricVersion) {
        var criterionBody = new LinkedHashMap<String, Object>();
        criterionBody.put("id", criterion.id());
        criterionBody.put("label", criterion.label());
        criterionBody.put("requirement", criterion.requirement());
        if (!criterion.description().isBlank()) criterionBody.put("description", criterion.description());
        var body = new LinkedHashMap<String, Object>();
        body.put("criterion", criterionBody);
        body.put(
                "chunks",
                chunks.stream()
                        .map(c -> Map.of("chunkId", c.chunkId(), "text", c.text()))
                        .toList());
        body.put("rubricVersion", rubricVersion);
        var row = gateway.post(tenantId, "/api/v1/internal/ai/judge", body);
        var guardrails = Records.doc(row.get("guardrails"));
        var provenance = Records.doc(row.get("provenance"));
        return new Judgement(
                Records.text(row, "criterionId"),
                row.get("score") instanceof Number n ? n.doubleValue() : 0,
                Records.documents(row.get("evidence")).stream()
                        .map(e -> new Judgement.Evidence(Records.text(e, "chunkId"), Records.text(e, "quote")))
                        .toList(),
                strings(row.get("strengths")),
                strings(row.get("gaps")),
                Records.text(row, "rationale"),
                Boolean.TRUE.equals(row.get("insufficientEvidence")),
                !Boolean.FALSE.equals(guardrails.get("grounded")),
                new Judgement.Provenance(
                        Records.text(provenance, "model"),
                        Records.text(provenance, "promptKey"),
                        Records.text(provenance, "promptVersion"),
                        Records.text(provenance, "traceId")));
    }

    private static double[] vector(Object row) {
        if (!(row instanceof List<?> numbers)) throw new IllegalStateException("Malformed vector");
        var vector = new double[numbers.size()];
        for (int i = 0; i < vector.length; i++) vector[i] = ((Number) numbers.get(i)).doubleValue();
        return vector;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
    }
}
