package com.openhire.offer.domain;

import static com.openhire.offer.domain.Fixtures.NOW;
import static com.openhire.offer.domain.Fixtures.terms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfferRendererTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-10-15");

    private OfferRenderer.Rendered render(String name, String cover) {
        return OfferRenderer.render(
                DefaultTemplates.standard("t1", NOW),
                DefaultTemplates.clauses("t1", NOW),
                name,
                "Senior Engineer",
                "Northwind Payments",
                terms("24"),
                cover,
                TODAY);
    }

    @Test
    void mergesApprovedTermsAndClausesIntoPlainText() {
        var letter = render("Priya Das", "We loved meeting you.").text();

        assertThat(letter)
                .contains("Dear Priya Das,")
                .contains("Base pay: INR 24")
                .contains("Joining date: 16 November 2026")
                .contains("Reporting to: Meera Nair")
                .contains("We loved meeting you.")
                .contains("Confidentiality")
                .contains("valid until 22 October 2026")
                .doesNotContain("{{");
    }

    @Test
    void theChecksumIsStableAndChangesWithTheContent() {
        var first = render("Priya Das", "Hello");
        assertThat(render("Priya Das", "Hello").sha256()).isEqualTo(first.sha256());
        assertThat(render("Priya Das", "Hello!").sha256()).isNotEqualTo(first.sha256());
        assertThat(first.sha256()).hasSize(64);
    }

    @Test
    void valuesAreInsertedLiterallyEvenWhenTheyLookLikeMergeFields() {
        var letter = render("{{terms.base}} $1 \\ Das", "").text();
        assertThat(letter).contains("Dear {{terms.base}} $1 \\ Das,");
    }

    @Test
    void unknownMergeFieldsAreRejectedWhenATemplateIsSaved() {
        assertThatThrownBy(() -> OfferRenderer.checked("Hello {{candidate.ssn}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown merge field");
        assertThat(OfferRenderer.checked("Hello {{ candidate.name }}")).contains("candidate.name");
        assertThatThrownBy(() -> OfferRenderer.checked("  ")).hasMessageContaining("cannot be empty");
    }

    @Test
    void aTemplateWithoutClausesStillRenders() {
        var template = OfferTemplate.create(
                "t1", "short", "Short", "IN", "ALL", "Hi {{candidate.name}}\n{{clauses}}", List.of(), "x", NOW);
        var letter = OfferRenderer.render(template, List.of(), "Priya", "Eng", "Co", terms("24"), null, TODAY);
        assertThat(letter.text()).isEqualTo("Hi Priya\n");
    }
}
