package com.openhire.aiworkers.shared.adapter.out.http;

import org.bson.Document;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Internal service-to-service calls authenticated with the shared service secret. */
public final class InternalClient {
    private final RestClient client;

    public InternalClient(String baseUrl, String secret, int readTimeoutMillis) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(readTimeoutMillis);
        client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Service-Secret", secret)
                .requestFactory(factory)
                .build();
    }

    public Document post(String tenantId, String path, Object body) {
        var response = client.post()
                .uri(path)
                .header("X-Tenant-Id", tenantId)
                .body(body)
                .retrieve()
                .body(Document.class);
        if (response == null) throw new IllegalStateException("Empty response from " + path);
        return response;
    }

    public Document get(String path, Object... variables) {
        return client.get().uri(path, variables).retrieve().body(Document.class);
    }
}
