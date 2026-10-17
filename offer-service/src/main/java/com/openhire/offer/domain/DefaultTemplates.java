package com.openhire.offer.domain;

import java.time.Instant;
import java.util.List;

/** Seeded per tenant on first use so a new workspace can send an offer immediately. */
public final class DefaultTemplates {
    public static final String STANDARD = "standard";

    private DefaultTemplates() {}

    public static OfferTemplate standard(String tenantId, Instant now) {
        return OfferTemplate.create(
                tenantId,
                STANDARD,
                "Standard offer letter",
                "IN",
                "ALL",
                """
                {{offer.date}}

                Dear {{candidate.name}},

                We are delighted to offer you the position of {{terms.designation}} at {{company.name}}.

                {{coverNote}}

                Your terms
                Designation: {{terms.designation}}
                Location: {{terms.location}}
                Reporting to: {{terms.reportsTo}}
                Joining date: {{terms.joiningDate}}
                Base pay: {{terms.currency}} {{terms.base}}
                Variable pay: {{terms.currency}} {{terms.variable}}
                Joining bonus: {{terms.currency}} {{terms.bonus}}

                {{clauses}}

                This offer is valid until {{terms.expiryDate}}. To accept, open the link we sent you and confirm with your full name.

                Sincerely,
                The {{company.name}} hiring team
                """,
                List.of("confidentiality", "background-check", "probation"),
                "system",
                now);
    }

    public static List<OfferClause> clauses(String tenantId, Instant now) {
        return List.of(
                OfferClause.create(
                        tenantId,
                        "confidentiality",
                        "Confidentiality",
                        "You agree to keep company and customer information confidential, during and after your employment.",
                        now),
                OfferClause.create(
                        tenantId,
                        "background-check",
                        "Background verification",
                        "This offer is subject to satisfactory verification of the information you provided.",
                        now),
                OfferClause.create(
                        tenantId,
                        "probation",
                        "Probation",
                        "Your first six months are a probation period, after which your employment is confirmed in writing.",
                        now));
    }
}
