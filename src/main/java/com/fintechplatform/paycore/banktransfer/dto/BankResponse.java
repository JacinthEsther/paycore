package com.fintechplatform.paycore.banktransfer.dto;

/** A bank a customer can send money to: its institution code and name. */
public record BankResponse(String code, String name) {
}
