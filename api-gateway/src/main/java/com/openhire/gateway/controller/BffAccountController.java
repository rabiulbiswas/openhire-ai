package com.openhire.gateway.controller;

import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.data.redis.ReactiveRedisIndexedSessionRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Account-and-security endpoints owned by the browser-facing BFF. */
@RestController
@RequestMapping("/api/v1/me")
public class BffAccountController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.gateway.security.MemberProfileClient profiles;

    private static final String PROFILE_NAME = "openhire.profile.name";
    private static final String PROFILE_TITLE = "openhire.profile.title";
    private static final String PROFILE_TIMEZONE = "openhire.profile.timezone";
    private static final String PROFILE_LANGUAGE = "openhire.profile.language";
    private static final String SESSION_DEVICE = "openhire.session.device";
    private static final String SESSION_LOCATION = "openhire.session.location";

    private final ReactiveRedisIndexedSessionRepository sessions;

    public BffAccountController(ReactiveRedisIndexedSessionRepository sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    Mono<MeProfile> profile(Mono<Principal> principal, WebSession session) {
        return principal
                .flatMap(value -> {
                    var current = profileFor(value, session);
                    if (profiles == null) return Mono.just(current);
                    return profiles.name(value)
                            .map(name -> new MeProfile(name, current.title(), current.timezone(), current.language()))
                            .defaultIfEmpty(current)
                            .onErrorReturn(current);
                })
                .defaultIfEmpty(profileFor(null, session));
    }

    @PatchMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> updateProfile(@RequestBody MeProfile profile, WebSession session, Mono<Principal> principal) {
        requireText(profile.name(), "name");
        requireText(profile.timezone(), "timezone");
        requireText(profile.language(), "language");
        if (profile.name().trim().length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is too long");
        // Persist first. Never report a session-only rename as a successful profile update.
        var persisted = principal.flatMap(value -> profiles == null
                ? Mono.empty()
                : profiles.update(value, profile.name().trim()));
        return persisted.then(Mono.defer(() -> saveProfilePreferences(profile, session)));
    }

    private Mono<Void> saveProfilePreferences(MeProfile profile, WebSession session) {
        session.getAttributes().put(PROFILE_NAME, profile.name().trim());
        session.getAttributes()
                .put(
                        PROFILE_TITLE,
                        profile.title() == null ? "" : profile.title().trim());
        session.getAttributes().put(PROFILE_TIMEZONE, profile.timezone().trim());
        session.getAttributes().put(PROFILE_LANGUAGE, profile.language().trim());
        return session.save();
    }

    @GetMapping("/sessions")
    Mono<List<SessionInfo>> browserSessions(
            Mono<Principal> principal,
            WebSession current,
            @RequestHeader(name = "User-Agent", defaultValue = "Browser") String userAgent) {
        rememberCurrentDevice(current, userAgent);
        return principal
                .flatMap(value -> sessions.findByPrincipalName(value.getName()).map(found -> found.values().stream()
                        .map(session -> toSessionInfo(session, current.getId()))
                        .sorted(Comparator.comparing(SessionInfo::current)
                                .reversed()
                                .thenComparing(SessionInfo::lastActiveAt, Comparator.reverseOrder()))
                        .toList()))
                .defaultIfEmpty(List.of(currentSession(current, userAgent)));
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> signOutOtherSessions(Mono<Principal> principal, WebSession current) {
        return principal
                .flatMap(value -> sessions.findByPrincipalName(value.getName()))
                .flatMapMany(found -> Flux.fromIterable(found.keySet()))
                .filter(id -> !id.equals(current.getId()))
                .flatMap(sessions::deleteById)
                .then();
    }

    private MeProfile profileFor(Principal principal, WebSession session) {
        var attributes = principal instanceof OAuth2AuthenticationToken oauth
                ? oauth.getPrincipal().getAttributes()
                : Map.<String, Object>of();
        return new MeProfile(
                session.getAttributeOrDefault(
                        PROFILE_NAME,
                        claim(attributes, "name", claim(attributes, "preferred_username", "Local administrator"))),
                session.getAttributeOrDefault(PROFILE_TITLE, ""),
                session.getAttributeOrDefault(PROFILE_TIMEZONE, "Asia/Kolkata"),
                session.getAttributeOrDefault(PROFILE_LANGUAGE, "English"));
    }

    private void rememberCurrentDevice(WebSession session, String userAgent) {
        session.getAttributes().putIfAbsent(SESSION_DEVICE, summarizeDevice(userAgent));
        session.getAttributes().putIfAbsent(SESSION_LOCATION, "Current network");
    }

    private SessionInfo toSessionInfo(ReactiveRedisIndexedSessionRepository.RedisSession session, String currentId) {
        return new SessionInfo(
                session.getId(),
                session.getAttributeOrDefault(SESSION_DEVICE, "Browser session"),
                session.getAttributeOrDefault(SESSION_LOCATION, "Unknown location"),
                session.getLastAccessedTime(),
                session.getId().equals(currentId));
    }

    private SessionInfo currentSession(WebSession session, String userAgent) {
        return new SessionInfo(session.getId(), summarizeDevice(userAgent), "Current network", Instant.now(), true);
    }

    private String summarizeDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Browser";
        var browser = userAgent.contains("Edg/")
                ? "Edge"
                : userAgent.contains("Chrome/")
                        ? "Chrome"
                        : userAgent.contains("Firefox/")
                                ? "Firefox"
                                : userAgent.contains("Safari/") ? "Safari" : "Browser";
        var os = userAgent.contains("Mac OS X")
                ? "macOS"
                : userAgent.contains("Windows")
                        ? "Windows"
                        : userAgent.contains("Android")
                                ? "Android"
                                : userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS" : "device";
        return browser + " on " + os;
    }

    private String claim(Map<String, Object> attributes, String name, String fallback) {
        var value = attributes.get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    public record MeProfile(String name, String title, String timezone, String language) {}

    public record SessionInfo(String id, String device, String location, Instant lastActiveAt, boolean current) {}
}
