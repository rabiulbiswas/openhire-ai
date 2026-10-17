package com.openhire.support;

import org.bson.Document;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class ServiceClient {
    private final RestClient client;

    public ServiceClient(String url, String secret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        client = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Internal-Service-Secret", secret)
                .requestFactory(factory)
                .build();
    }

    public Document post(String path, Object body) {
        return client.post().uri(path).body(body).retrieve().body(Document.class);
    }

    public Document get(String path, Object... variables) {
        return client.get().uri(path, variables).retrieve().body(Document.class);
    }

    public RestClient raw() {
        return client;
    }
}
