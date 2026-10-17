package com.openhire.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConsentServiceTest {
    private final Rig rig = new Rig();
    private final Actor officer = new Actor("u1", "Dev");
    private static final String TEXT = "I agree to my application being processed by the hiring team.";

    @Test
    void withoutAPublishedTextTheBuiltInDefaultAndVersionAreServed() {
        var active = rig.consent.active("t1", "PROCESSING");
        assertThat(active).containsEntry("version", "privacy-v1").containsEntry("builtIn", true);
        assertThat((String) active.get("text")).contains("A person makes hiring decisions");
    }

    @Test
    void publishingRetiresThePreviousVersionAndAuditsIt() {
        var first = rig.consent.publish("t1", officer, "PROCESSING", "en", TEXT);
        var second = rig.consent.publish("t1", officer, "PROCESSING", "en", TEXT + " Version two.");

        assertThat(first).containsEntry("version", "privacy-v2").containsEntry("status", "ACTIVE");
        assertThat(second).containsEntry("version", "privacy-v3");
        assertThat(rig.consent.texts("t1"))
                .extracting(t -> t.get("version") + ":" + t.get("status"))
                .containsExactlyInAnyOrder("privacy-v2:RETIRED", "privacy-v3:ACTIVE");
        assertThat(rig.consent.active("t1", "PROCESSING")).containsEntry("version", "privacy-v3");
        assertThat(rig.sink.of("CONSENT_TEXT_PUBLISHED")).hasSize(2);
        assertThat(rig.consent.active("t2", "PROCESSING")).containsEntry("version", "privacy-v1");
    }

    @Test
    void textLengthIsEnforced() {
        assertThatThrownBy(() -> rig.consent.publish("t1", officer, "PROCESSING", "en", "too short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rig.consent.publish("t1", officer, "PROCESSING", "en", "x".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consentRecordsComeFromAuditEvents() {
        var granted = Rig.auditEvent("t1", "CONSENT_GRANTED", "app_1", "cand_1", "2026-10-01T10:00:00Z");
        Maps.map(Maps.map(granted).get("payload"))
                .put("details", Maps.of("version", "privacy-v2", "purpose", "PROCESSING"));
        rig.ledger.ingest(granted);
        rig.ledger.ingest(Rig.auditEvent("t1", "OFFER_SENT", "app_1", "cand_1", "2026-10-01T11:00:00Z"));

        var records = rig.consent.records("t1", "cand_1");
        assertThat(records).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("status", "GRANTED")
                .containsEntry("version", "privacy-v2")
                .containsEntry("candidateId", "cand_1"));
    }
}
