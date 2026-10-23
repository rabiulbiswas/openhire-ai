package com.openhire.applicationservice.service.port;

import java.util.Optional;

/** Where the active processing-consent text is published. */
public interface ConsentSource {
    /** The active consent, or empty when the publisher is unreachable or has nothing for the tenant. */
    Optional<Consent> active(String tenantId);

    record Consent(String version, String text) {}
}
