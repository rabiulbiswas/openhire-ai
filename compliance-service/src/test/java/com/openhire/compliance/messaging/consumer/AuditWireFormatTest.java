package com.openhire.compliance.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.openhire.compliance.service.AuditLedger;
import com.openhire.compliance.service.PrivacyService;
import com.openhire.compliance.service.Projections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuditWireFormatTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuditLedger ledger = mock(AuditLedger.class);
    private final ComplianceListeners listener =
            new ComplianceListeners(ledger, mock(Projections.class), mock(PrivacyService.class), json);

    @Test
    void canonicalAndSingleLegacyStringLayerPreserveTheSameAuditEnvelope() {
        Map<String, Object> envelope =
                Map.of("eventType", "audit.event.recorded.v1", "tenantId", "fixture", "eventId", "event-1");
        var encoded = json.writeValueAsString(envelope);
        listener.onAudit(encoded);
        listener.onAudit(json.writeValueAsString(encoded));
        verify(ledger, times(2)).ingest(envelope);
    }

    @Test
    void malformedAndRepeatedlyWrappedValuesStillFailInsteadOfBeingDropped() {
        for (String input :
                new String[] {"[]", "null", "{broken", json.writeValueAsString(json.writeValueAsString("{}"))}) {
            assertThatThrownBy(() -> listener.onAudit(input)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(ledger);
    }
}
