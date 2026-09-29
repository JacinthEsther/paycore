package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.customer.exception.InvalidPhoneNumberException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibPhoneNumberServiceTest {

    private PhoneNumberService phoneNumberService;

    @BeforeEach
    void setUp() {
        phoneNumberService = new LibPhoneNumberService();
    }

    @Test
    void shouldNormalizeNigerianLocalNumberToE164() {

        String result = phoneNumberService.normalize(
                "08012345678",
                "NG"
        );

        assertThat(result)
                .isEqualTo("+2348012345678");
    }

    @Test
    void shouldNormalizeNigerianInternationalNumber() {

        String result = phoneNumberService.normalize(
                "+2348012345678",
                "NG"
        );

        assertThat(result)
                .isEqualTo("+2348012345678");
    }

    @Test
    void shouldNormalizeNumberWithSpaces() {

        String result = phoneNumberService.normalize(
                "0801 234 5678",
                "NG"
        );

        assertThat(result)
                .isEqualTo("+2348012345678");
    }

    @Test
    void shouldNormalizeNumberWithFormattingCharacters() {

        String result = phoneNumberService.normalize(
                "+234 801-234-5678",
                "NG"
        );

        assertThat(result)
                .isEqualTo("+2348012345678");
    }

    @Test
    void shouldNormalizeGhanaianNumber() {

        String result = phoneNumberService.normalize(
                "0241234567",
                "GH"
        );

        assertThat(result)
                .isEqualTo("+233241234567");
    }

    @Test
    void shouldNormalizeUgandanNumber() {

        String result = phoneNumberService.normalize(
                "0771234567",
                "UG"
        );

        assertThat(result)
                .isEqualTo("+256771234567");
    }

    @Test
    void shouldNormalizeCanadianNumber() {

        String result = phoneNumberService.normalize(
                "+14165551234",
                "CA"
        );

        assertThat(result)
                .isEqualTo("+14165551234");
    }

    @Test
    void shouldRejectInvalidPhoneNumber() {

        assertThatThrownBy(() ->
                phoneNumberService.normalize(
                        "12345",
                        "NG"
                )
        )
                .isInstanceOf(InvalidPhoneNumberException.class)
                .hasMessage("Invalid phone number");
    }

    @Test
    void shouldRejectMalformedPhoneNumber() {

        assertThatThrownBy(() ->
                phoneNumberService.normalize(
                        "abc123",
                        "NG"
                )
        )
                .isInstanceOf(InvalidPhoneNumberException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "'08012345678', 'NG', '+2348012345678'",
            "'+2348012345678', 'NG', '+2348012345678'",
            "'+234 801 234 5678', 'NG', '+2348012345678'",
            "'0801 234 5678', 'NG', '+2348012345678'"
    })
    void shouldNormalizeEquivalentNigerianNumbers(
            String input,
            String country,
            String expected
    ) {

        String result =
                phoneNumberService.normalize(input, country);

        assertThat(result)
                .isEqualTo(expected);
    }


}