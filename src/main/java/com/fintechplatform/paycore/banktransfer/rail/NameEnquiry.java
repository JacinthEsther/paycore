package com.fintechplatform.paycore.banktransfer.rail;

import java.util.UUID;

public record NameEnquiry(String bankCode, String accountNumber, UUID requestedBy) {
}
