package com.openhire.integration.service;

import java.time.Instant;
import java.util.Map;

public final class CalendarPorts {
    private CalendarPorts() {}

    public record Credentials(String clientId, String clientSecret, String redirectUri, String directory) {}

    public record Grant(String accessToken, String refreshToken, Instant expiresAt) {}

    public record Pending(
            String state,
            String tenant,
            String actor,
            String connector,
            long version,
            long grantRevision,
            String verifier,
            Instant expiresAt) {}

    public record SavedGrant(String tenant, String connector, long configVersion, long revision, Grant grant) {}

    public interface Secrets {
        Credentials credentials(String reference);
    }

    public interface Store {
        void pending(Pending pending);

        Pending consume(String state, String tenant, String actor, Instant now);

        SavedGrant load(String tenant, String connector);

        boolean save(SavedGrant grant, long expectedRevision);

        void disconnect(String tenant, String connector);
    }

    public interface Provider {
        String authorize(String provider, Credentials credentials, String state, String challenge);

        Grant exchange(String provider, Credentials credentials, String code, String verifier, Instant now);

        Grant refresh(String provider, Credentials credentials, Grant previous, Instant now);

        Map<String, Object> list(String provider, String accessToken);

        Map<String, Object> write(
                String provider,
                String accessToken,
                String stableId,
                String remoteId,
                com.openhire.integration.domain.CalendarEvent event,
                boolean cancel);
    }
}
