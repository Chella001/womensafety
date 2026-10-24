package com.womensafety.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.womensafety.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    private final PhoneNumbers phones = new PhoneNumbers();

    @Test
    void normalisesDifferentWritingsToE164() {
        assertThat(phones.toE164("+91 98765-43210", null, "phoneNumber")).isEqualTo("+919876543210");
        assertThat(phones.toE164("(+91) 98765 43210", "IN", "phoneNumber")).isEqualTo("+919876543210");
        assertThat(phones.toE164("098765 43210", "IN", "phoneNumber")).isEqualTo("+919876543210");
        assertThat(phones.toE164("98765 43210", "IN", "phoneNumber")).isEqualTo("+919876543210");
        assertThat(phones.toE164("+44 7911 123456", "IN", "phoneNumber")).isEqualTo("+447911123456");
    }

    @Test
    void rejectsInvalidNumbers() {
        for (String bad : new String[] {"12345", "+1234", "abcdefghij", "+91 12345", "+999 123456789", "98765"}) {
            assertThatThrownBy(() -> phones.toE164(bad, "IN", "phoneNumber"))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void withoutACountryCodeOrRegion_theNumberIsRejected() {
        assertThatThrownBy(() -> phones.toE164("9876543210", null, "phoneNumber"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void strictE164Check() {
        assertThat(phones.isValidE164("+919876543210")).isTrue();
        assertThat(phones.isValidE164("+447911123456")).isTrue();
        assertThat(phones.isValidE164("919876543210")).isFalse();
        assertThat(phones.isValidE164("+91 9876543210")).isFalse();
        assertThat(phones.isValidE164("+91123")).isFalse();
        assertThat(phones.isValidE164(null)).isFalse();
    }

    @Test
    void regionAndMasking() {
        assertThat(phones.regionOf("+919876543210")).isEqualTo("IN");
        assertThat(phones.regionOf("+442071838750")).isEqualTo("GB");
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91•••••••210");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("••••");
    }
}
