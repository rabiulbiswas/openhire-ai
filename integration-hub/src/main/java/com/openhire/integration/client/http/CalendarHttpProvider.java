package com.openhire.integration.client.http;

import com.openhire.integration.service.CalendarPorts.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Fixed provider origins, bounded calls, no redirects, and sanitized failures. */
@Component
public class CalendarHttpProvider implements Provider {
    public interface Transport {
        Response send(HttpRequest request) throws Exception;
    }

    public record Response(int status, String body) {}

    private static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/calendar.events";
    private final ObjectMapper json;
    private final Transport transport;
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CalendarHttpProvider(ObjectMapper json) {
        this(json, defaultTransport());
    }

    public CalendarHttpProvider(ObjectMapper json, Transport transport) {
        this.json = json;
        this.transport = transport;
    }

    private static Transport defaultTransport() {
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return request -> {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new java.io.IOException("Response too large");
                return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        };
    }

    private static boolean google(String provider) {
        if (!List.of("GOOGLE_CALENDAR", "MICROSOFT_365").contains(provider))
            throw new IllegalArgumentException("Unsupported provider");
        return provider.equals("GOOGLE_CALENDAR");
    }

    private static String microsoft(Credentials c) {
        String directory = c.directory() == null ? "organizations" : c.directory();
        if (!directory.matches("organizations|[a-fA-F0-9-]{36}"))
            throw new IllegalArgumentException("Invalid Microsoft directory");
        return "https://login.microsoftonline.com/" + directory + "/oauth2/v2.0/";
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public String authorize(String provider, Credentials c, String state, String challenge) {
        var query = new LinkedHashMap<String, String>();
        query.put("client_id", c.clientId());
        query.put("redirect_uri", c.redirectUri());
        query.put("response_type", "code");
        query.put("state", state);
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        query.put("scope", google(provider) ? GOOGLE_SCOPE : "offline_access Calendars.ReadWrite");
        if (google(provider)) {
            query.put("access_type", "offline");
            query.put("prompt", "consent");
        }
        return (google(provider) ? "https://accounts.google.com/o/oauth2/v2/auth" : microsoft(c) + "authorize") + "?"
                + form(query);
    }

    public Grant exchange(String provider, Credentials c, String code, String verifier, Instant now) {
        var data = new LinkedHashMap<String, String>();
        data.put("grant_type", "authorization_code");
        data.put("code", code);
        data.put("code_verifier", verifier);
        data.put("redirect_uri", c.redirectUri());
        return token(provider, c, data, null, now);
    }

    public Grant refresh(String provider, Credentials c, Grant previous, Instant now) {
        if (previous.refreshToken() == null || previous.refreshToken().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reconnect this calendar account");
        return token(
                provider,
                c,
                new LinkedHashMap<>(Map.of("grant_type", "refresh_token", "refresh_token", previous.refreshToken())),
                previous.refreshToken(),
                now);
    }

    private Grant token(String provider, Credentials c, Map<String, String> data, String oldRefresh, Instant now) {
        data.put("client_id", c.clientId());
        data.put("client_secret", c.clientSecret());
        var request = HttpRequest.newBuilder(
                        URI.create(google(provider) ? "https://oauth2.googleapis.com/token" : microsoft(c) + "token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(data)))
                .build();
        Map<String, Object> result = send(provider, request);
        if (!(result.get("access_token") instanceof String access)
                || access.isBlank()
                || !(result.get("expires_in") instanceof Number expiry)
                || expiry.longValue() < 1
                || !"Bearer".equalsIgnoreCase(String.valueOf(result.get("token_type"))))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned an invalid token response");
        // Providers may omit scope when it is unchanged. Explicitly reduced grants must be rejected.
        if (result.get("scope") instanceof String scope) {
            var scopes = List.of(scope.split(" "));
            if (!scopes.contains(google(provider) ? GOOGLE_SCOPE : "Calendars.ReadWrite")
                    && !(!google(provider) && scopes.contains("https://graph.microsoft.com/Calendars.ReadWrite")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Calendar permission was not granted");
        }
        return new Grant(
                access,
                result.get("refresh_token") instanceof String r ? r : oldRefresh,
                now.plusSeconds(Math.min(expiry.longValue(), 86400)));
    }

    public Map<String, Object> list(String provider, String accessToken) {
        String url = google(provider)
                ? "https://www.googleapis.com/calendar/v3/calendars/primary/events?maxResults=50&singleEvents=true&orderBy=startTime&timeMin="
                        + encode(Instant.now().toString())
                : "https://graph.microsoft.com/v1.0/me/events?$top=50&$select=id,subject,start,end,webLink";
        return send(
                provider,
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String provider, HttpRequest request) {
        var semaphore = bulkheads.computeIfAbsent(provider, ignored -> new Semaphore(4));
        if (!semaphore.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Calendar provider is busy; retry later");
        try {
            var response = transport.send(request);
            if (request.method().equals("DELETE") && (response.status() == 404 || response.status() == 410))
                return Map.of();
            if (response.status() == 409
                    && google(provider)
                    && request.method().equals("POST")
                    && request.uri().getHost().equals("www.googleapis.com")) throw new DuplicateCalendarEvent();
            if (response.status() == 401 || response.status() == 403 || response.status() == 400)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Provider rejected authorization or permissions; reconnect the calendar");
            if (response.status() == 429)
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "Calendar provider rate limit reached; retry later");
            if (response.status() < 200 || response.status() > 299)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Calendar provider is unavailable");
            return response.body().isBlank() ? Map.of() : json.readValue(response.body(), Map.class);
        } catch (DuplicateCalendarEvent duplicate) {
            throw duplicate;
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Calendar request interrupted");
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Calendar provider request failed");
        } finally {
            semaphore.release();
        }
    }

    private static final class DuplicateCalendarEvent extends RuntimeException {}

    public Map<String, Object> write(
            String provider,
            String accessToken,
            String stableId,
            String remoteId,
            com.openhire.integration.domain.CalendarEvent event,
            boolean cancel) {
        if (!stableId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid stable event identity");
        if (cancel && remoteId == null) return Map.of();
        if (remoteId != null && (remoteId.isBlank() || remoteId.length() > 2048))
            throw new IllegalArgumentException("Invalid provider event ID");
        boolean google = google(provider);
        String base = google
                ? "https://www.googleapis.com/calendar/v3/calendars/primary/events"
                : "https://graph.microsoft.com/v1.0/me/events";
        String path = remoteId == null ? "" : "/" + encode(remoteId).replace("+", "%20");
        String suffix = google ? "?sendUpdates=all&conferenceDataVersion=1" : "";
        var builder = HttpRequest.newBuilder(URI.create(base + path + suffix))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json");
        if (cancel) return send(provider, builder.DELETE().build());
        var body = new LinkedHashMap<String, Object>();
        if (google) {
            body.put("summary", event.title());
            body.put("start", Map.of("dateTime", event.start().toString(), "timeZone", event.timezone()));
            body.put("end", Map.of("dateTime", event.end().toString(), "timeZone", event.timezone()));
            body.put(
                    "attendees",
                    event.attendees().stream()
                            .map(email -> Map.of("email", email))
                            .toList());
            if (remoteId == null) {
                body.put("id", stableId);
                if (event.onlineMeeting())
                    body.put(
                            "conferenceData",
                            Map.of(
                                    "createRequest",
                                    Map.of(
                                            "requestId",
                                            stableId,
                                            "conferenceSolutionKey",
                                            Map.of("type", "hangoutsMeet"))));
            }
        } else {
            body.put("subject", event.title());
            // UTC avoids Windows/IANA timezone mapping differences while preserving the exact instants.
            body.put(
                    "start",
                    Map.of(
                            "dateTime",
                            java.time.LocalDateTime.ofInstant(event.start(), java.time.ZoneOffset.UTC)
                                    .toString(),
                            "timeZone",
                            "UTC"));
            body.put(
                    "end",
                    Map.of(
                            "dateTime",
                            java.time.LocalDateTime.ofInstant(event.end(), java.time.ZoneOffset.UTC)
                                    .toString(),
                            "timeZone",
                            "UTC"));
            body.put(
                    "attendees",
                    event.attendees().stream()
                            .map(email -> Map.of("emailAddress", Map.of("address", email), "type", "required"))
                            .toList());
            if (remoteId == null) {
                body.put(
                        "transactionId",
                        java.util
                                .UUID
                                .nameUUIDFromBytes(stableId.getBytes(StandardCharsets.US_ASCII))
                                .toString());
                if (event.onlineMeeting()) {
                    body.put("isOnlineMeeting", true);
                    body.put("onlineMeetingProvider", "teamsForBusiness");
                }
            }
        }
        var request = builder.method(
                        remoteId == null ? "POST" : "PATCH",
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            var result = send(provider, request);
            // A repeated Microsoft transaction may return the original receipt; reconcile the latest desired data.
            if (!google && remoteId == null && result.get("id") instanceof String id)
                return write(provider, accessToken, stableId, id, event, false);
            return result;
        } catch (DuplicateCalendarEvent duplicate) {
            return write(provider, accessToken, stableId, stableId, event, false);
        }
    }
}
