package com.openhire.communication.client.http;

import com.openhire.communication.service.port.CommunicationPorts.DraftRequest;
import com.openhire.communication.service.port.CommunicationPorts.DraftTurn;
import com.openhire.communication.service.port.CommunicationPorts.DraftingUnavailableException;
import com.openhire.communication.service.port.CommunicationPorts.ReplyDrafter;
import com.openhire.support.Access;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the AI gateway's message-draft task. A local model is slow, so the read timeout is generous. */
@Component
class HttpReplyDrafter implements ReplyDrafter {
    private final RestClient gateway;

    HttpReplyDrafter(
            Access access,
            @Value("${openhire.ai.gateway-url}") String gatewayUrl,
            @Value("${openhire.ai.draft-timeout-ms:45000}") int timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeoutMs);
        gateway = RestClient.builder()
                .baseUrl(gatewayUrl)
                .defaultHeader("X-Internal-Service-Secret", access.secret())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String draftReply(String tenantId, DraftRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("purpose", "REPLY");
        body.put("candidateFirstName", request.candidateFirstName());
        if (request.jobTitle() != null) body.put("jobTitle", request.jobTitle());
        body.put("thread", turns(request.thread()));
        body.put("channel", request.channel());
        try {
            var response = gateway.post()
                    .uri("/api/v1/internal/ai/message-draft")
                    .header("X-Tenant-Id", tenantId)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null || !(response.get("text") instanceof String text) ? null : text;
        } catch (RestClientException unavailable) {
            throw new DraftingUnavailableException("The AI gateway could not draft a reply", unavailable);
        }
    }

    private static List<Map<String, String>> turns(List<DraftTurn> thread) {
        return thread.stream()
                .map(turn -> Map.of("from", turn.from(), "text", turn.text()))
                .toList();
    }
}
