package com.openhire.compliance.service;

import com.openhire.compliance.domain.Fairness;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.service.port.CompliancePorts.ApplicationDirectory;
import com.openhire.compliance.service.port.CompliancePorts.Cipher;
import com.openhire.compliance.service.port.CompliancePorts.SelfIdentificationStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Voluntary self-identification, stored encrypted and only ever read back by the candidate or as aggregates. */
public class SelfIdentificationService {
    private final SelfIdentificationStore store;
    private final Cipher cipher;
    private final ApplicationDirectory directory;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public SelfIdentificationService(
            SelfIdentificationStore store, Cipher cipher, ApplicationDirectory directory, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.directory = directory;
        this.clock = clock;
    }

    public Map<String, Object> put(String tenantId, String applicationId, String receipt, Map<String, Object> input) {
        var candidateId = directory.verifyReceipt(tenantId, applicationId, receipt);
        var clean = new LinkedHashMap<String, Object>();
        for (var dimension : Fairness.DIMENSIONS) {
            var value = Maps.text(input, dimension);
            if (value == null || value.isBlank()) continue;
            var code = value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            if (!code.matches("[A-Z0-9_]{1,40}"))
                throw new IllegalArgumentException(dimension + " must be a short code such as PREFER_NOT_TO_SAY");
            clean.put(dimension, code);
        }
        store.save(
                tenantId,
                applicationId,
                candidateId,
                cipher.encrypt(json.writeValueAsString(clean), context(tenantId, applicationId)),
                Times.format(clock.instant()));
        return clean;
    }

    public Map<String, Object> get(String tenantId, String applicationId, String receipt) {
        directory.verifyReceipt(tenantId, applicationId, receipt);
        return store.ciphertext(tenantId, applicationId)
                .map(c -> decrypt(tenantId, applicationId, c))
                .orElseGet(LinkedHashMap::new);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> decrypt(String tenantId, String applicationId, String ciphertext) {
        return json.readValue(cipher.decrypt(ciphertext, context(tenantId, applicationId)), Map.class);
    }

    static String context(String tenantId, String applicationId) {
        return tenantId + "|" + applicationId;
    }
}
