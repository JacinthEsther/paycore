package com.fintechplatform.paycore.funding.provider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CardPaymentRecords {

    CardPaymentRecord saveIfAbsent(CardPaymentRecord record);

    Optional<CardPaymentRecord> find(String merchantReference);

    CardPaymentRecord markRefunded(String merchantReference, String refundReference, Instant at);

    List<CardPaymentRecord> between(Instant from, Instant to);
}
