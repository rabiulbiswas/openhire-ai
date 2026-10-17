package com.openhire.applicationservice.workflow;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class BulkImportParserTest {
    @Test
    void handlesBomQuotedCommasEscapedQuotesAndMultilineCells() {
        var rows = BulkImportController.parseCsv(
                "\uFEFFname,email,processingConsent\r\n\"Doe, Jane\",jane@example.test,true\r\n\"A \"\"quoted\"\"\nname\",a@example.test,false\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).isEqualTo(List.of("Doe, Jane", "jane@example.test", "true"));
        assertThat(rows.get(2).getFirst()).isEqualTo("A \"quoted\"\nname");
    }

    @Test
    void rejectsUnclosedAndMisplacedQuotes() {
        for (String csv : List.of("\"unterminated", "a\"b,c", "\"closed\"extra,c"))
            assertThatThrownBy(() -> BulkImportController.parseCsv(csv)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedFields() {
        assertThatThrownBy(() -> BulkImportController.parseCsv("a".repeat(10001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
