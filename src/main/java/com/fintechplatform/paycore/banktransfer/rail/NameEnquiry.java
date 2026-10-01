package com.fintechplatform.paycore.banktransfer.rail;

import java.util.UUID;

/**
 * Who holds this account at that bank? requestedBy is the PayCore
 * customer asking; a real rail ignores it, the simulator uses it to
 * recognise the customer's own Test Bank account.
 */
public record NameEnquiry(String bankCode, String accountNumber, UUID requestedBy) {
}
