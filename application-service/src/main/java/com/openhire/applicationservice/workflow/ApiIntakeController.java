package com.openhire.applicationservice.workflow;

import static com.openhire.support.Records.*;

import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import java.util.Collection;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Machine intake validates the key at its owner on every write, including revoked keys. */
@RestController
@RequestMapping("/api/v1/public/api-applications")
public class ApiIntakeController {
    private final SubmissionService service;
    private final ServiceClient identity;
    private final String secret;

    public ApiIntakeController(
            SubmissionService service,
            Access access,
            @Value("${openhire.identity-url:http://localhost:8081}") String url) {
        this.service = service;
        secret = access.secret();
        identity = new ServiceClient(url, secret);
    }

    private String tenant(String key) {
        if (key.isBlank() || key.length() > 512 || key.indexOf('.') < 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        Document result;
        try {
            result = identity.raw()
                    .post()
                    .uri("/api/v1/internal/api-keys/validate")
                    .header("X-Internal-Secret", secret)
                    .body(Map.of("apiKey", key))
                    .retrieve()
                    .body(Document.class);
        } catch (RestClientResponseException invalid) {
            if (invalid.getStatusCode().is4xxClientError())
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service unavailable");
        }
        if (!(result.get("scopes") instanceof Collection<?> scopes) || !scopes.contains("applications:write"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "applications:write scope required");
        return required(result, "organizationId", 100);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object json(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Document input)
            throws Exception {
        String tenant = tenant(apiKey);
        input.put("source", "PUBLIC_API");
        return Map.of(
                "id",
                service.submit(tenant, "api:" + apiKey.substring(0, apiKey.indexOf('.')), key, input, null)
                        .getString("_id"));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object multipart(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam Map<String, String> fields,
            @RequestPart MultipartFile resume)
            throws Exception {
        String tenant = tenant(apiKey);
        var input = new Document();
        input.putAll(fields);
        input.put("source", "PUBLIC_API");
        if (fields.containsKey("answers")) input.put("answers", Answers.parse(fields.get("answers")));
        return Map.of(
                "id",
                service.submit(tenant, "api:" + apiKey.substring(0, apiKey.indexOf('.')), key, input, resume)
                        .getString("_id"));
    }
}
