package com.openhire.applicationservice.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AnswersTest {
    @Test
    void parsesAJsonObject() {
        assertThat(Answers.parse("{\"q1\":\"yes\"}").getString("q1")).isEqualTo("yes");
    }

    @Test
    void malformedJsonIsABadRequest() {
        assertThatThrownBy(() -> Answers.parse("\\{\\}"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
