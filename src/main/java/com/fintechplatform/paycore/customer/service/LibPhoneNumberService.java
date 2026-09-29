package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.customer.exception.InvalidPhoneNumberException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.stereotype.Service;

@Service
public class LibPhoneNumberService implements PhoneNumberService {

    private final PhoneNumberUtil phoneNumberUtil;

    public LibPhoneNumberService() {
        this.phoneNumberUtil = PhoneNumberUtil.getInstance();
    }

    @Override
    public String normalize(String phoneNumber, String countryCode) {

        try {
            Phonenumber.PhoneNumber parsedNumber =
                    phoneNumberUtil.parse(phoneNumber, countryCode);

            if (!phoneNumberUtil.isValidNumber(parsedNumber)) {
                throw new InvalidPhoneNumberException(
                        "Invalid phone number"
                );
            }

            return phoneNumberUtil.format(
                    parsedNumber,
                    PhoneNumberUtil.PhoneNumberFormat.E164
            );

        } catch (NumberParseException exception) {
            throw new InvalidPhoneNumberException(
                    "Invalid phone number format"
            );
        }
    }
}