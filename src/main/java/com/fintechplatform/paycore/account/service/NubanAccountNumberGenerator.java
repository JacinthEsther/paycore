package com.fintechplatform.paycore.account.service;

import com.fintechplatform.paycore.account.config.AccountProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class NubanAccountNumberGenerator implements AccountNumberGenerator {

    private static final int SERIAL_LENGTH = 9;

    private static final int[] WEIGHTS = {3, 7, 3, 3, 7, 3, 3, 7, 3, 3, 7, 3};

    private final SecureRandom random = new SecureRandom();
    private final String institutionCode;

    public NubanAccountNumberGenerator(AccountProperties properties) {

        String code = properties.getInstitutionCode();

        if (code == null || !code.matches("\\d{3}")) {
            throw new IllegalStateException(
                    "paycore.accounts.institution-code must be exactly 3 digits"
            );
        }

        this.institutionCode = code;
    }

    @Override
    public String generate() {

        StringBuilder serial = new StringBuilder(SERIAL_LENGTH);

        for (int i = 0; i < SERIAL_LENGTH; i++) {
            serial.append(random.nextInt(10));
        }

        return serial.toString() + checkDigit(institutionCode, serial.toString());
    }

    public static boolean isValid(String institutionCode, String accountNumber) {

        if (accountNumber == null || !accountNumber.matches("\\d{10}")) {
            return false;
        }

        String serial = accountNumber.substring(0, SERIAL_LENGTH);

        return accountNumber.charAt(SERIAL_LENGTH)
                == Character.forDigit(checkDigit(institutionCode, serial), 10);
    }

    static int checkDigit(String institutionCode, String serial) {

        String digits = institutionCode + serial;
        int sum = 0;

        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += Character.digit(digits.charAt(i), 10) * WEIGHTS[i];
        }

        return (10 - sum % 10) % 10;
    }
}
