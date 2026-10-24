package com.womensafety.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class PersonNameValidatorTest {

    private record Holder(@PersonName(min = 2, max = 60) String name) {
    }

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean valid(String name) {
        return validator.validate(new Holder(name)).isEmpty();
    }

    @Test
    void acceptsRealWorldNames() {
        for (String ok : new String[] {"Al", "Asha Rao", "  Asha   Rao  ", "Mary-Jane O'Brien", "Anne de la Cruz",
                "José Álvarez", "प्रिया शर्मा", "இளவரசி", "Dr. Priya K", "x".repeat(60)}) {
            assertThat(valid(ok)).as("'%s' should be valid", ok).isTrue();
        }
    }

    @Test
    void rejectsEmptyShortLongAndSuspiciousNames() {
        for (String bad : new String[] {"", "   ", "A", " A ", "x".repeat(61), "12345", "Priya123", "<script>", "a@b.c",
                "Robert'); DROP TABLE", "Tab\u0000Null", "-Leading", "9Lives"}) {
            assertThat(valid(bad)).as("'%s' should be invalid", bad).isFalse();
        }
    }

    @Test
    void lineBreaksInsideANameAreNormalisedToASpace() {
        assertThat(valid("Asha\nRao")).isTrue();
        assertThat(PersonNames.normalise("Asha\nRao")).isEqualTo("Asha Rao");
    }

    @Test
    void nullIsLeftToNotBlank() {
        assertThat(valid(null)).isTrue();
    }

    @Test
    void normaliseTrimsAndCollapsesWhitespace() {
        assertThat(PersonNames.normalise("  Asha \t  Rao ")).isEqualTo("Asha Rao");
    }
}
