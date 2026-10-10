package com.fintechplatform.paycore.banktransfer.simulator;

import com.fintechplatform.paycore.banktransfer.rail.Bank;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.banktransfer.rail.BankRailException;
import com.fintechplatform.paycore.banktransfer.rail.NameEnquiry;
import com.fintechplatform.paycore.banktransfer.rail.RailPayment;
import com.fintechplatform.paycore.banktransfer.rail.RailResult;
import com.fintechplatform.paycore.banktransfer.rail.RailStatus;
import com.fintechplatform.paycore.banktransfer.simulator.RailSessionRecords.RailSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fintechplatform.paycore.banktransfer.rail.RailSettlementLine;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class SimulatedBankRail implements BankRail {

    public static final String PROVIDER_NAME = "SIMULATED";

    public static final long REJECTED_MINOR_ENDING = 99;

    public static final long PAID_THEN_TIMEOUT_MINOR_ENDING = 98;

    public static final long LOST_IN_TRANSIT_MINOR_ENDING = 97;

    public static final long STATUS_UNAVAILABLE_MINOR_ENDING = 96;

    public static final long RETURNED_LATER_MINOR_ENDING = 93;

    private static final Logger log = LoggerFactory.getLogger(SimulatedBankRail.class);

    private final TestBank testBank;
    private final RailSessionRecords sessions;

    public SimulatedBankRail(TestBank testBank, RailSessionRecords sessions) {
        this.testBank = testBank;
        this.sessions = sessions;

        log.warn(
                "Bank rail is SIMULATED: transfers to and from other banks are not real. "
                        + "Never use this with real customers."
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<Bank> banks() {
        return List.of(TestBank.BANK);
    }

    @Override
    public Optional<String> nameEnquiry(NameEnquiry enquiry) {

        if (!TestBank.BANK.code().equals(enquiry.bankCode())) {
            return Optional.empty();
        }

        return testBank.holderOf(enquiry.accountNumber(), enquiry.requestedBy());
    }

    @Override
    public RailResult send(RailPayment payment) {

        long kobo = payment.amount().amountMinor() % 100;

        if (sessions.find(payment.sessionId()).isEmpty() && kobo == LOST_IN_TRANSIT_MINOR_ENDING) {
            throw new BankRailException(
                    PROVIDER_NAME + " timed out (simulated: amounts ending in .97 never arrive)", null
            );
        }

        boolean rejected = kobo == REJECTED_MINOR_ENDING;
        boolean returnedLater = kobo == RETURNED_LATER_MINOR_ENDING;

        RailSession stored =
                sessions.saveIfAbsent(RailSession.outbound(
                        payment.sessionId(),
                        payment.bankCode(),
                        payment.accountNumber(),
                        payment.accountName(),
                        payment.amount().amountMinor(),
                        payment.amount().currency().code(),
                        payment.narration(),
                        rejected || returnedLater ? "FAILED" : "SUCCESSFUL",
                        rejected
                                ? "Beneficiary bank rejected the transfer (simulated: amounts ending in .99 always fail)"
                                : returnedLater
                                        ? "Returned by the beneficiary bank after acceptance (simulated: amounts ending in .93)"
                                        : null
                ));

        if (returnedLater) {
            return RailResult.success();
        }

        if (kobo == PAID_THEN_TIMEOUT_MINOR_ENDING || kobo == STATUS_UNAVAILABLE_MINOR_ENDING) {
            throw new BankRailException(
                    PROVIDER_NAME + " timed out after paying (simulated: amounts ending in ." + kobo + ")", null
            );
        }

        return "SUCCESSFUL".equals(stored.status()) ? RailResult.success() : RailResult.rejected(stored.reason());
    }

    @Override
    public RailStatus status(String sessionId) {

        RailSession session = sessions.find(sessionId).orElse(null);

        if (session == null) {
            return RailStatus.notFound();
        }

        if (session.amountMinor() % 100 == STATUS_UNAVAILABLE_MINOR_ENDING) {
            throw new BankRailException(
                    PROVIDER_NAME + " status query unavailable (simulated: amounts ending in .96)", null
            );
        }

        return "SUCCESSFUL".equals(session.status()) ? RailStatus.successful() : RailStatus.failed(session.reason());
    }

    @Override
    public List<RailSettlementLine> settlementReport(Instant from, Instant to) {

        return sessions.between(from, to).stream()
                .map(session -> new RailSettlementLine(
                        session.sessionId(), session.direction(), session.bankCode(), session.accountNumber(),
                        session.amountMinor(), session.currency(), session.status(), session.reason(),
                        session.createdAt()
                ))
                .toList();
    }

    @Override
    public Optional<InboundTransfer> redeliverInbound(String sessionId) {

        return sessions.find(sessionId)
                .filter(session -> "INBOUND".equals(session.direction()) && "SUCCESSFUL".equals(session.status()))
                .map(session -> {
                    sessions.markNotified(sessionId);
                    return new InboundTransfer(
                            PROVIDER_NAME,
                            session.sessionId(),
                            session.accountNumber(),
                            BigDecimal.valueOf(session.amountMinor(), 2),
                            session.currency(),
                            new Counterparty(session.senderName(), session.senderBank(), session.senderAccountNumber()),
                            session.narration()
                    );
                });
    }
}
