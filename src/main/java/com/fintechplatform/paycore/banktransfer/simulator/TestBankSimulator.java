package com.fintechplatform.paycore.banktransfer.simulator;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.exception.AccountNotFoundException;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.banktransfer.config.RailProperties;
import com.fintechplatform.paycore.banktransfer.dto.SimulatedInboundRequest;
import com.fintechplatform.paycore.banktransfer.dto.TestBankAccountResponse;
import com.fintechplatform.paycore.banktransfer.exception.SimulatorLimitExceededException;
import com.fintechplatform.paycore.banktransfer.rail.SessionIds;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Test Bank's side of an inbound transfer: the signed-in customer sends
 * money from their own Test Bank account, in their own name, to a PayCore
 * account. It reaches the ledger exactly the way a real bank's transfer
 * would, as an inbound rail notification with a session id: PayCore does
 * not treat it any differently.
 *
 * Because the money is pretend, Test Bank sends at most a fixed total to
 * any one PayCore account. That cap belongs to the simulator; PayCore
 * itself never refuses a real inbound transfer for being large.
 */
@Service
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class TestBankSimulator {

    private final TestBank testBank;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final RailProperties properties;

    public TestBankSimulator(
            TestBank testBank,
            LedgerService ledgerService,
            AccountRepository accountRepository,
            RailProperties properties
    ) {
        this.testBank = testBank;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    /**
     * The customer's Test Bank account, and how much more it may send to
     * their PayCore account (accountId, which must be theirs).
     */
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

        return ledgerService.receiveInboundTransfer(
                new InboundTransfer(
                        SimulatedBankRail.PROVIDER_NAME,
                        SessionIds.next(TestBank.BANK.code()),
                        destination.getAccountNumber(),
                        request.amount(),
                        currency.code(),
                        new Counterparty(senderName, TestBank.BANK.name(), testBank.accountNumberFor(customerId)),
                        request.narration()
                )
        );
    }

    private Money sentTo(Account account, Currency currency) {
        return Money.ofMinor(ledgerService.inboundTotal(account.getId(), SimulatedBankRail.PROVIDER_NAME), currency);
    }

    private static Money remaining(Money limit, Money sent) {
        return sent.isGreaterThanOrEqualTo(limit) ? Money.ofMinor(0, limit.currency()) : limit.subtract(sent);
    }
}
