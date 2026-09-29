package com.fintechplatform.paycore.account.service;

import com.fintechplatform.paycore.account.config.AccountProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NubanAccountNumberGeneratorTest {

    @Test
    void shouldComputeNubanCheckDigit() {

        // Worked by hand with weights 3,7,3,3,7,3,3,7,3,3,7,3:
        // 0,5,8 | 1..9 -> 0+35+24+3+14+9+12+35+18+21+56+27 = 254 -> 10 - 4 = 6
        assertThat(NubanAccountNumberGenerator.checkDigit("058", "123456789"))
                .isEqualTo(6);

        // 9,9,9 | 0..0,1 -> 27+63+27+3 = 120 -> (10 - 0) % 10 = 0
        assertThat(NubanAccountNumberGenerator.checkDigit("999", "000000001"))
                .isZero();
    }

    @Test
    void shouldGenerateTenDigitNumbersWithValidCheckDigit() {

        NubanAccountNumberGenerator generator = generator("999");

        for (int i = 0; i < 1_000; i++) {

            String accountNumber = generator.generate();

            assertThat(accountNumber).matches("\\d{10}");
            assertThat(NubanAccountNumberGenerator.isValid("999", accountNumber))
                    .isTrue();
        }
    }

    @Test
    void shouldNotRepeatNumbersInPractice() {

        NubanAccountNumberGenerator generator = generator("999");
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }

        // 10^9 serials: a handful of collisions in 10k draws would already
        // mean the randomness is broken.
        assertThat(seen).hasSizeGreaterThan(9_990);
    }

    @Test
    void shouldDetectSingleDigitTypo() {

        assertThat(NubanAccountNumberGenerator.isValid("058", "1234567896")).isTrue();
        assertThat(NubanAccountNumberGenerator.isValid("058", "1234567806")).isFalse();
        assertThat(NubanAccountNumberGenerator.isValid("058", "1234567895")).isFalse();
    }

    @Test
    void shouldTieCheckDigitToInstitutionCode() {

        assertThat(NubanAccountNumberGenerator.isValid("059", "1234567896")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345678", "12345678901", "abcdefghij"})
    void shouldRejectMalformedNumbers(String accountNumber) {

        assertThat(NubanAccountNumberGenerator.isValid("058", accountNumber)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "99", "9999", "9a9"})
    void shouldRejectInvalidInstitutionCode(String code) {

        assertThatThrownBy(() -> generator(code))
                .isInstanceOf(IllegalStateException.class);
    }

    private static NubanAccountNumberGenerator generator(String institutionCode) {

        AccountProperties properties = new AccountProperties();
        properties.setInstitutionCode(institutionCode);

        return new NubanAccountNumberGenerator(properties);
    }
}
