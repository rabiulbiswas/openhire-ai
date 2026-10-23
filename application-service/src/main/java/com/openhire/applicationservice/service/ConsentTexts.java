package com.openhire.applicationservice.service;

import com.openhire.applicationservice.service.port.ConsentSource;
import com.openhire.applicationservice.service.port.ConsentSource.Consent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The consent text applicants see and the version a submission must carry. Both come from one cached lookup per
 * tenant, so what is validated is always what was last served. Falls back to the built-in text when the publisher
 * is unreachable.
 */
@Service
public class ConsentTexts {
    static final long TTL_MILLIS = 60_000;
    static final String BUILT_IN_TEXT =
            "I agree to the processing of my application and resume for this role. A person makes hiring decisions.";

    private record Cached(Consent consent, long expiresAt) {}

    private final ConsentSource source;
    private final Consent fallback;
    private final String defaultTenant;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public ConsentTexts(
            ConsentSource source,
            @Value("${openhire.consent-version:privacy-v1}") String builtInVersion,
            @Value("${openhire.tenant.default-id:}") String defaultTenant) {
        this(source, builtInVersion, defaultTenant, System::currentTimeMillis);
    }

    ConsentTexts(ConsentSource source, String builtInVersion, String defaultTenant, LongSupplier clock) {
        this.source = source;
        this.fallback = new Consent(builtInVersion, BUILT_IN_TEXT);
        this.defaultTenant = defaultTenant;
        this.clock = clock;
    }

    public Consent active(String tenant) {
        String key = tenant == null || tenant.isBlank() ? defaultTenant : tenant;
        if (key == null || key.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tenant ID is required to load consent");
        }
        long now = clock.getAsLong();
        var cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) return cached.consent();
        var fresh = source.active(key).orElse(fallback);
        cache.put(key, new Cached(fresh, now + TTL_MILLIS));
        return fresh;
    }

    public String version(String tenant) {
        return active(tenant).version();
    }
}
