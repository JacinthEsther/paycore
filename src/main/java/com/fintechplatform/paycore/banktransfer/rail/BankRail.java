package com.fintechplatform.paycore.banktransfer.rail;

import com.fintechplatform.paycore.ledger.service.InboundTransfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BankRail {

    String providerName();

    List<Bank> banks();

    Optional<String> nameEnquiry(NameEnquiry enquiry);

    RailResult send(RailPayment payment);

    RailStatus status(String sessionId);

    List<RailSettlementLine> settlementReport(Instant from, Instant to);

    Optional<InboundTransfer> redeliverInbound(String sessionId);
}
