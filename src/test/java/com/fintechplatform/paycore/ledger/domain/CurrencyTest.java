package com.fintechplatform.paycore.ledger.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyTest {

    @Test
    void shouldTakeMinorUnitFromIso4217() {

        assertThat(Currency.of("NGN")).isEqualTo(new Currency("NGN", 2));
        assertThat(Currency.of("JPY").minorUnit()).isZero();
        assertThat(Currency.of("KWD").minorUnit()).isEqualTo(3);
    }

    @Test
    void shouldNormalizeCode() {

        assertThat(Currency.of(" ngn ").code()).isEqualTo("NGN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "NG", "ABC", "₦"})
    void shouldRejectUnknownCodes(String code) {

        assertThatThrownBy(() -> Currency.of(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectCurrencyWithoutMinorUnit() {

        assertThatThrownBy(() -> Currency.of("XAU"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidConstructorArguments() {

        assertThatThrownBy(() -> new Currency("ngn", 2))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Currency("NGN", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
