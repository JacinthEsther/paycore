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
import com.fintechplatform.paycore.banktransfer.rail.RailStatus;
import com.fintechplatform.paycore.banktransfer.rail.SessionIds;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.exception.IdempotencyConflictException;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.OutboundTransfer;
import com.fintechplatform.paycore.payment.config.PaymentProperties;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.enums.PaymentType;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BankTransferService {

    public static final Bank PAYCORE = new Bank("100999", "PayCore");

    static final String NOT_CONFIRMED_YET =
            "We have not been able to confirm this transfer with the beneficiary bank yet. "
                    + "Our operations team is checking it.";

    static final String NEVER_ARRIVED =
            "The transfer did not reach the beneficiary bank; your money was returned";

    static final String NEVER_SENT =
            "The transfer could not be sent; your money was returned";

    private static final Logger log = LoggerFactory.getLogger(BankTransferService.class);

    private final ObjectProvider<BankRail> rails;
    private final LedgerService ledgerService;
    private final PaymentService paymentService;
    private final PaymentProperties paymentProperties;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public BankTransferService(
            ObjectProvider<BankRail> rails,
            LedgerService ledgerService,
            PaymentService paymentService,
            PaymentProperties paymentProperties,
            AccountRepository accountRepository,
            CustomerRepository customerRepository
    ) {
        this.rails = rails;
        this.ledgerService = ledgerService;
        this.paymentService = paymentService;
        this.paymentProperties = paymentProperties;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    public List<BankResponse> banks() {

        List<BankResponse> banks = new ArrayList<>();
        banks.add(new BankResponse(PAYCORE.code(), PAYCORE.name()));

        BankRail rail = rails.getIfAvailable();

        if (rail != null) {
            rail.banks().forEach(bank -> banks.add(new BankResponse(bank.code(), bank.name())));
        }

        return banks;
    }

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

        Optional<Payment> previous = paymentService.findByKey(customerId, request.idempotencyKey());

        if (previous.isPresent()) {
            return toResponse(sameTransfer(previous.get(), sourceAccountId, request));
        }

        Bank bank = requireBank(rail, request.bankCode());

        String beneficiaryName =
                enquire(rail, new NameEnquiry(bank.code(), request.accountNumber(), customerId))
                        .orElseThrow(BeneficiaryNotFoundException::new);

        String sessionId = SessionIds.next(PAYCORE.code());

        Payment payment =
                paymentService.initiateOutbound(
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
                        ),
                        bank.code()
                );

        if (!sessionId.equals(payment.getExternalReference())) {
            return toResponse(payment);
        }

        payment = paymentService.markProcessing(payment.getId());

        RailResult result;

        try {
            result =
                    rail.send(new RailPayment(
                            sessionId,
                            bank.code(),
                            request.accountNumber(),
                            beneficiaryName,
                            payment.amount(),
                            payment.getNarration(),
                            senderName(customerId)
                    ));
        } catch (RuntimeException exception) {
            log.warn(
                    "Outbound transfer {} (session {}) has an unknown outcome; checking its status later: {}",
                    payment.getReference(), sessionId, exception.getMessage()
            );
            return toResponse(paymentService.markUnknown(payment.getId()));
        }

        if (result.accepted()) {
            return toResponse(paymentService.succeed(payment.getId()));
        }

        return toResponse(paymentService.failOutbound(payment.getId(), rejectionReason(result.reason())));
    }

    public Payment settle(Payment payment) {

        RailStatus status = currentStatus(payment);

        return switch (status == null ? RailStatus.Outcome.PENDING : status.outcome()) {

            case SUCCESSFUL -> paymentService.succeed(payment.getId());

            case FAILED -> paymentService.failOutbound(payment.getId(), rejectionReason(status.reason()));

            case NOT_FOUND -> payment.getCreatedAt().isBefore(Instant.now().minus(paymentProperties.getNotFoundGrace()))
                    ? paymentService.failOutbound(payment.getId(), NEVER_ARRIVED)
                    : paymentService.stillUnknown(payment.getId(), NOT_CONFIRMED_YET);

            case PENDING -> paymentService.stillUnknown(payment.getId(), NOT_CONFIRMED_YET);
        };
    }

    public Optional<Payment> abandonUnsent(UUID paymentId, Instant staleBefore) {
        return paymentService.failOutboundIfUnsent(paymentId, staleBefore, NEVER_SENT);
    }

    public RailStatus currentStatus(Payment payment) {

        BankRail rail = rails.getIfAvailable();

        if (rail == null || !rail.providerName().equals(payment.getProvider())) {
            log.warn("Cannot check transfer {}: {} is not the configured rail",
                    payment.getReference(), payment.getProvider());
            return null;
        }

        try {
            return rail.status(payment.getExternalReference());
        } catch (RuntimeException exception) {
            log.warn("Status check of transfer {} with {} failed: {}",
                    payment.getReference(), rail.providerName(), exception.getMessage());
            return null;
        }
    }

    private static Payment sameTransfer(Payment payment, UUID sourceAccountId, OutboundTransferRequest request) {

        boolean same =
                payment.getType() == PaymentType.OUTBOUND_TRANSFER
                        && payment.getAccountId().equals(sourceAccountId)
                        && payment.getCurrency().equals(request.currency())
                        && payment.amount().equals(Money.ofMajor(request.amount(), Currency.of(request.currency())))
                        && payment.getCounterpartyAccountNumber().equals(request.accountNumber())
                        && (payment.getCounterpartyBankCode() == null
                                || payment.getCounterpartyBankCode().equals(request.bankCode()));

        if (!same) {
            throw new IdempotencyConflictException();
        }

        return payment;
    }

    public OutboundTransferResponse toResponse(Payment payment) {

        TransactionResponse debit = ledgerService.getTransaction(payment.getCustomerId(), payment.getLedgerTransactionId());

        String transferStatus = switch (payment.getStatus()) {
            case SUCCEEDED -> OutboundTransferResponse.SUCCESSFUL;
            case FAILED -> OutboundTransferResponse.FAILED;
            default -> OutboundTransferResponse.PENDING;
        };

        String reversalReference =
                payment.getReversalTransactionId() == null
                        ? null
                        : ledgerService.getTransaction(payment.getCustomerId(), payment.getReversalTransactionId()).reference();

        return new OutboundTransferResponse(
                debit,
                transferStatus,
                payment.getStatus() == PaymentStatus.SUCCEEDED ? null : payment.getFailureReason(),
                reversalReference,
                payment.getId(),
                payment.getReference(),
                payment.getStatus()
        );
    }

    private static String rejectionReason(String reason) {
        return reason == null || reason.isBlank() ? "The beneficiary bank rejected the transfer" : reason;
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
