package com.fintechplatform.paycore.funding.provider;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryCardPaymentRecords implements CardPaymentRecords {

    private final Map<String, CardPaymentRecord> records = new ConcurrentHashMap<>();

    @Override
    public CardPaymentRecord saveIfAbsent(CardPaymentRecord record) {
        return records.computeIfAbsent(record.merchantReference(), key -> record);
    }

    @Override
    public Optional<CardPaymentRecord> find(String merchantReference) {
        return Optional.ofNullable(records.get(merchantReference));
    }

    @Override
    public List<CardPaymentRecord> between(Instant from, Instant to) {

        return records.values().stream()
                .filter(record -> !record.createdAt().isBefore(from) && record.createdAt().isBefore(to))
                .sorted(Comparator.comparing(CardPaymentRecord::createdAt))
                .toList();
    }

    @Override
    public CardPaymentRecord markRefunded(String merchantReference, String refundReference, Instant at) {

        return records.computeIfPresent(merchantReference, (key, record) ->
                CardPaymentRecord.CONFIRMED.equals(record.status())
                        ? new CardPaymentRecord(
                                record.merchantReference(), record.providerReference(), record.amountMinor(),
                                record.currency(), CardPaymentRecord.REFUNDED, record.reason(), refundReference,
                                record.createdAt(), at, record.settled()
                        )
                        : record
        );
    }
}
