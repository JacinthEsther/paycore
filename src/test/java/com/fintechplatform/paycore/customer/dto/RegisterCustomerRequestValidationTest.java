package com.fintechplatform.paycore.customer.dto;

import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterCustomerRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {

        validatorFactory =
                Validation.buildDefaultValidatorFactory();

        validator =
                validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {

        validatorFactory.close();
    }

    @Test
    void shouldAcceptValidRequest() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "08012345678",
                        "Password123!"
                );

        Set<?> violations =
                validator.validate(request);

        assertThat(violations)
                .isEmpty();
    }

    @Test
    void shouldRejectBlankFirstName() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "08012345678",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectBlankLastName() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "",
                        "esther@example.com",
                        "NG",
                        "08012345678",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectInvalidEmail() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "not-an-email",
                        "NG",
                        "08012345678",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectBlankEmail() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "",
                        "NG",
                        "08012345678",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectInvalidCountryCodeLength() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "N",
                        "08012345678",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectCountryCodeLongerThanTwoCharacters() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NGA",
                        "08012345678",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectBlankPhoneNumber() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "",
                        "Password123!"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectShortPassword() {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "08012345678",
                        "1234567"
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }

    @Test
    void shouldRejectLongPassword() {

        String password = "a".repeat(129);

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "08012345678",
                        password
                );

        assertThat(validator.validate(request))
                .isNotEmpty();
    }


}