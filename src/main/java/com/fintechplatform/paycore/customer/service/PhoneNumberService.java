package com.fintechplatform.paycore.customer.service;

public interface PhoneNumberService {

    String normalize(String phoneNumber, String countryCode);
}