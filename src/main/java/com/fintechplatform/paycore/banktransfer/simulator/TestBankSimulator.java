package com.fintechplatform.paycore.banktransfer.simulator;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.exception.AccountNotFoundException;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.banktransfer.config.RailProperties;
import com.fintechplatform.paycore.banktransfer.dto.SimulatedInboundRequest;
import com.fintechplatform.paycore.banktransfer.dto.TestBankAccountResponse;
import com.fintechplatform.paycore.banktransfer.exception.SimulatorLimitExceededException;
import com.fintechplatform.paycore.banktransfer.exception.InboundNotificationLostException;
import com.fintechplatform.paycore.banktransfer.rail.SessionIds;
import com.fintechplatform.paycore.banktransfer.service.BankTransferService;
import com.fintechplatform.paycore.banktransfer.simulator.RailSessionRecords.RailSession;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class TestBankSimulator {

    public static final long NOTIFICATION_LOST_MINOR_ENDING = 95;

    private final TestBank testBank;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final RailProperties properties;
    private final RailSessionRecords sessions;

    public TestBankSimulator(
            TestBank testBank,
            LedgerService ledgerService,
            AccountRepository accountRepository,
            RailProperties properties,
            RailSessionRecords sessions
    ) {
        this.testBank = testBank;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.sessions = sessions;
    }

    public TestBankAccountResponse account(UUID customerId, UUID accountId) {

        Account account =
                accountRepository
                        .findByIdAndCustomerId(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        Currency currency = Currency.of(account.getCurrency());
        Money limit = Money.ofMajor(properties.getSimulatorInboundLimitPerAccount(), currency);

        return new TestBankAccountResponse(
                TestBank.BANK.code(),
                TestBank.BANK.name(),
                testBank.accountNumberFor(customerId),
                testBank.holderNameFor(customerId).orElse("Test Bank customer"),
                currency.code(),
                limit.toMajor(),
                remaining(limit, sentTo(account, currency)).toMajor()
        );
    }

    public TransactionResponse send(UUID customerId, SimulatedInboundRequest request) {

        Account destination =
                accountRepository
                        .findByAccountNumber(request.destinationAccountNumber())
                        .orElseThrow(() -> new InboundTransferRejectedException("Account number not found"));

        Currency currency = Currency.of(destination.getCurrency());
        Money amount = Money.ofMajor(request.amount(), currency);
        Money limit = Money.ofMajor(properties.getSimulatorInboundLimitPerAccount(), currency);
        Money sent = sentTo(destination, currency);

        if (sent.add(amount).isGreaterThan(limit)) {
            throw new SimulatorLimitExceededException(limit, remaining(limit, sent));
        }

        String senderName = testBank.holderNameFor(customerId).orElse("Test Bank customer");
        String sessionId = SessionIds.next(TestBank.BANK.code());

        String senderAccount = testBank.accountNumberFor(customerId);
        boolean notificationLost = amount.amountMinor() % 100 == NOTIFICATION_LOST_MINOR_ENDING;

        sessions.saveIfAbsent(new RailSession(
                sessionId, "INBOUND", BankTransferService.PAYCORE.code(), destination.getAccountNumber(), null,
                amount.amountMinor(), currency.code(), request.narration(), "SUCCESSFUL", null,
                senderName, TestBank.BANK.name(), senderAccount, !notificationLost, Instant.now()
        ));

        if (notificationLost) {
            throw new InboundNotificationLostException(sessionId);
        }

        try {
            return ledgerService.receiveInboundTransfer(
                    new InboundTransfer(
                            SimulatedBankRail.PROVIDER_NAME,
                            sessionId,
                            destination.getAccountNumber(),
                            request.amount(),
                            currency.code(),
                            new Counterparty(senderName, TestBank.BANK.name(), senderAccount),
                            request.narration()
                    )
            );
        } catch (InboundTransferRejectedException exception) {
            sessions.markReturned(sessionId, exception.getMessage());
            throw exception;
        }
    }

    private Money sentTo(Account account, Currency currency) {
        return Money.ofMinor(ledgerService.inboundTotal(account.getId(), SimulatedBankRail.PROVIDER_NAME), currency);
    }

    private static Money remaining(Money limit, Money sent) {
        return sent.isGreaterThanOrEqualTo(limit) ? Money.ofMinor(0, limit.currency()) : limit.subtract(sent);
    }
}
