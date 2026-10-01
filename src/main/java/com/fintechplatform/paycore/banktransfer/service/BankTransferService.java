package com.fintechplatform.paycore.banktransfer.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.banktransfer.dto.BankResponse;
import com.fintechplatform.paycore.banktransfer.dto.NameEnquiryResponse;
import com.fintechplatform.paycore.banktransfer.dto.OutboundTransferRequest;
import com.fintechplatform.paycore.banktransfer.dto.OutboundTransferResponse;
import com.fintechplatform.paycore.banktransfer.exception.BankTransfersDisabledException;
import com.fintechplatform.paycore.banktransfer.exception.BeneficiaryNotFoundException;
import com.fintechplatform.paycore.banktransfer.exception.UnknownBankException;
import com.fintechplatform.paycore.banktransfer.rail.Bank;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.banktransfer.rail.BankRailException;
import com.fintechplatform.paycore.banktransfer.rail.NameEnquiry;
import com.fintechplatform.paycore.banktransfer.rail.RailPayment;
import com.fintechplatform.paycore.banktransfer.rail.RailResult;
import com.fintechplatform.paycore.banktransfer.rail.SessionIds;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.OutboundTransfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sending money to other banks, and finding out who holds an account
 * before paying it (name enquiry), for PayCore accounts and accounts at
 * banks on the rail alike.
 *
 * An outbound transfer works like at any bank:
 *
 * <pre>
 * 1. retry of a transfer already made with this key? return it, pay nothing
 * 2. name enquiry: the beneficiary must exist; their name goes on the record
 * 3. ledger: DEBIT customer, CREDIT settlement, under the account lock
 * 4. rail: pay the beneficiary bank (no database transaction is open)
 * 5. rejected? the ledger reverses the debit and the customer has their money
 * </pre>
 *
 * Not transactional itself, so no row lock is held while the rail answers.
 */
@Service
public class BankTransferService {

    /** PayCore itself, as a bank in the list and in name enquiry. */
    public static final Bank PAYCORE = new Bank("100999", "PayCore");

    private static final Logger log = LoggerFactory.getLogger(BankTransferService.class);

    private final ObjectProvider<BankRail> rails;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public BankTransferService(
            ObjectProvider<BankRail> rails,
            LedgerService ledgerService,
            AccountRepository accountRepository,
            CustomerRepository customerRepository
    ) {
        this.rails = rails;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    /** PayCore first, then every bank the rail reaches (none without a rail). */
    public List<BankResponse> banks() {

        List<BankResponse> banks = new ArrayList<>();
        banks.add(new BankResponse(PAYCORE.code(), PAYCORE.name()));

        BankRail rail = rails.getIfAvailable();

        if (rail != null) {
            rail.banks().forEach(bank -> banks.add(new BankResponse(bank.code(), bank.name())));
        }

        return banks;
    }

    /**
     * The name on the account, so the customer can check who they are
     * paying. A PayCore account is looked up here; another bank's through
     * the rail.
     */
    @Transactional(readOnly = true)
    public NameEnquiryResponse nameEnquiry(UUID customerId, String bankCode, String accountNumber) {

        if (PAYCORE.code().equals(bankCode)) {

            Account account =
                    accountRepository
                            .findByAccountNumber(accountNumber)
                            .filter(found -> found.getStatus() == AccountStatus.ACTIVE)
                            .orElseThrow(BeneficiaryNotFoundException::new);

            return new NameEnquiryResponse(
                    PAYCORE.code(),
                    PAYCORE.name(),
                    accountNumber,
                    account.getCustomer().getFirstName() + " " + account.getCustomer().getLastName()
            );
        }

        BankRail rail = requireRail();
        Bank bank = requireBank(rail, bankCode);

        String name =
                enquire(rail, new NameEnquiry(bankCode, accountNumber, customerId))
                        .orElseThrow(BeneficiaryNotFoundException::new);

        return new NameEnquiryResponse(bank.code(), bank.name(), accountNumber, name);
    }

    public OutboundTransferResponse send(UUID customerId, UUID sourceAccountId, OutboundTransferRequest request) {

        BankRail rail = requireRail();

        Optional<TransactionResponse> previous = ledgerService.findOutbound(customerId, request.idempotencyKey());

        if (previous.isPresent()) {
            return outcomeOf(previous.get());
        }

        Bank bank = requireBank(rail, request.bankCode());

        String beneficiaryName =
                enquire(rail, new NameEnquiry(bank.code(), request.accountNumber(), customerId))
                        .orElseThrow(BeneficiaryNotFoundException::new);

        String sessionId = SessionIds.next(PAYCORE.code());

        TransactionResponse debit =
                ledgerService.postOutbound(
                        customerId,
                        sourceAccountId,
                        new OutboundTransfer(
                                request.amount(),
                                request.currency(),
                                request.idempotencyKey(),
                                request.narration(),
                                rail.providerName(),
                                sessionId,
                                new Counterparty(beneficiaryName, bank.name(), request.accountNumber())
                        )
                );

        // A concurrent duplicate of this request was debited first; it is
        // the one being paid, not this session.
        if (!sessionId.equals(debit.providerReference())) {
            return outcomeOf(debit);
        }

        RailResult result;

        try {
            result =
                    rail.send(new RailPayment(
                            sessionId,
                            bank.code(),
                            request.accountNumber(),
                            beneficiaryName,
                            Money.ofMajor(request.amount(), Currency.of(request.currency())),
                            debit.description(),
                            senderName(customerId)
                    ));
        } catch (RuntimeException exception) {
            // Outcome unknown: the beneficiary may have been paid. Reversing
            // now could pay the customer twice, so the debit stands until
            // the session is confirmed with the provider.
            log.error(
                    "Outbound transfer {} (session {}) has an unknown outcome and needs a status check: {}",
                    debit.reference(), sessionId, exception.getMessage()
            );
            return new OutboundTransferResponse(debit, OutboundTransferResponse.PENDING, null, null);
        }

        if (result.accepted()) {
            return new OutboundTransferResponse(debit, OutboundTransferResponse.SUCCESSFUL, null, null);
        }

        TransactionResponse reversal = ledgerService.reverseRejectedOutbound(debit.id(), result.reason());

        return new OutboundTransferResponse(
                ledgerService.getTransaction(customerId, debit.id()),
                OutboundTransferResponse.FAILED,
                result.reason(),
                reversal.reference()
        );
    }

    /** A transfer made earlier: successful unless it was reversed. */
    private static OutboundTransferResponse outcomeOf(TransactionResponse transaction) {

        boolean reversed = transaction.status() == LedgerTransactionStatus.REVERSED;

        return new OutboundTransferResponse(
                transaction,
                reversed ? OutboundTransferResponse.FAILED : OutboundTransferResponse.SUCCESSFUL,
                reversed ? "The transfer failed and the money was returned" : null,
                null
        );
    }

    private Optional<String> enquire(BankRail rail, NameEnquiry enquiry) {

        try {
            return rail.nameEnquiry(enquiry);
        } catch (BankRailException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BankRailException(rail.providerName() + " name enquiry failed", exception);
        }
    }

    private BankRail requireRail() {

        BankRail rail = rails.getIfAvailable();

        if (rail == null) {
            throw new BankTransfersDisabledException();
        }

        return rail;
    }

    private static Bank requireBank(BankRail rail, String bankCode) {

        return rail.banks().stream()
                .filter(bank -> bank.code().equals(bankCode))
                .findFirst()
                .orElseThrow(() -> new UnknownBankException(bankCode));
    }

    private String senderName(UUID customerId) {

        return customerRepository
                .findById(customerId)
                .map(customer -> customer.getFirstName() + " " + customer.getLastName())
                .orElse("PayCore customer");
    }
}
