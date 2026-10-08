package dev.miqui.messageservice.validation;

import dev.miqui.messageservice.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViolationsTest {

    @ParameterizedTest
    @ValueSource(strings = {"\0", "\0text", "te\0xt", "text\0", " \0 "})
    void rejectsNulAnywhereInText(String value) {
        var violations = new Violations();
        violations.text("content", value, Violations.CONTENT_MAX);

        var error = assertThrows(ApiException.BadInput.class, violations::throwIfAny);
        assertThat(error.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.getField()).isEqualTo("content");
            assertThat(violation.getDescription()).isEqualTo("content cannot contain NUL characters");
        });
    }

    @Test
    void preservesValidWhitespaceTrimmingAndUnicode() {
        var violations = new Violations();

        assertThat(violations.text("content", "  caf\u00e9\n ", Violations.CONTENT_MAX)).isEqualTo("caf\u00e9");
        assertThat(violations.email("email", " a@example.com ")).isEqualTo("a@example.com");
        violations.throwIfAny();
    }
}
