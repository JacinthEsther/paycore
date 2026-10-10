package com.fintechplatform.paycore.operations.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.common.persistence.ConstraintViolations;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.response.StaffTransactionResponse;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.operations.dto.AdjustmentRequestBody;
import com.fintechplatform.paycore.operations.dto.PaymentResolutionRequestBody;
import com.fintechplatform.paycore.operations.dto.OperationsRequestResponse;
import com.fintechplatform.paycore.operations.dto.ReversalRequestBody;
import com.fintechplatform.paycore.operations.entity.OperationsRequest;
import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;
import com.fintechplatform.paycore.operations.enums.OperationsRequestType;
import com.fintechplatform.paycore.operations.exception.DuplicateReversalRequestException;
import com.fintechplatform.paycore.operations.exception.OperationsRequestNotFoundException;
import com.fintechplatform.paycore.operations.repository.OperationsRequestRepository;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OperationsService {

    static final String PENDING_REVERSAL_CONSTRAINT = "uk_operations_requests_pending_reversal";

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);

    private static final int LIST_SIZE = 100;

    private final OperationsRequestRepository requestRepository;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final PaymentService paymentService;
    private final PaymentResolutionService paymentResolutionService;

    public OperationsService(
            OperationsRequestRepository requestRepository,
            LedgerService ledgerService,
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            PaymentService paymentService,
            PaymentResolutionService paymentResolutionService
    ) {
        this.requestRepository = requestRepository;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.paymentService = paymentService;
        this.paymentResolutionService = paymentResolutionService;
    }

    @Transactional
    public OperationsRequestResponse requestAdjustment(UUID makerId, AdjustmentRequestBody body) {

        Money amount = Money.ofMajor(body.amount(), Currency.of(body.currency()));

        ledgerService.requireAdjustable(makerId, body.accountId(), amount.currency().code());

        OperationsRequest request =
                requestRepository.save(
                        OperationsRequest.adjustment(
                                makerId,
                                body.accountId(),
                                body.direction(),
                                amount,
                                body.reason(),
                                body.customerDescription()
                        )
                );

        log.info("Adjustment request {} filed by {}", request.getId(), makerId);

        return toResponse(request);
    }

    @Transactional
    public OperationsRequestResponse requestReversal(UUID makerId, ReversalRequestBody body) {

        ledgerService.requireReversible(makerId, body.transactionId());

        OperationsRequest request;

        try {
            request =
                    requestRepository.saveAndFlush(
                            OperationsRequest.reversal(
                                    makerId,
                                    body.transactionId(),
                                    body.reason(),
                                    body.customerDescription()
                            )
                    );
        } catch (DataIntegrityViolationException exception) {

            if (ConstraintViolations.violates(exception, PENDING_REVERSAL_CONSTRAINT)) {
                throw new DuplicateReversalRequestException();
            }

            throw exception;
        }

        log.info("Reversal request {} filed by {}", request.getId(), makerId);

        return toResponse(request);
    }

    @Transactional
    public OperationsRequestResponse requestPaymentResolution(UUID makerId, PaymentResolutionRequestBody body) {
        return toResponse(paymentResolutionService.request(makerId, body));
    }

    @Transactional
    public OperationsRequestResponse approve(UUID checkerId, UUID requestId, String note) {

        OperationsRequest request =
                requestRepository
                        .findByIdForUpdate(requestId)
                        .orElseThrow(OperationsRequestNotFoundException::new);

        request.requireDecidableBy(checkerId);

        if (request.getType() == OperationsRequestType.PAYMENT_RESOLUTION) {

            UUID posted = paymentResolutionService.apply(request, checkerId).orElse(null);
            request.approve(checkerId, note, posted);

            log.info("Request {} approved by {}: payment {} resolved {}",
                    requestId, checkerId, request.getPaymentId(), request.getResolution());

            return toResponse(request);
        }

        StaffTransactionResponse posted =
                request.getType() == OperationsRequestType.ADJUSTMENT
                        ? ledgerService.postAdjustment(
                                request.getRequestedBy(),
                                checkerId,
                                request.getAccountId(),
                                request.getDirection(),
                                Money.ofMinor(request.getAmountMinor(), Currency.of(request.getCurrency())),
                                request.getReason(),
                                request.getCustomerDescription(),
                                request.ledgerKey()
                        )
                        : ledgerService.reverse(
                                request.getRequestedBy(),
                                checkerId,
                                request.getTransactionId(),
                                request.getReason(),
                                request.getCustomerDescription(),
                                request.ledgerKey()
                        );

        request.approve(checkerId, note, posted.transaction().id());

        log.info("Request {} approved by {}: posted {}", requestId, checkerId, posted.transaction().reference());

        return toResponse(request);
    }

    @Transactional
    public OperationsRequestResponse reject(UUID checkerId, UUID requestId, String note) {

        OperationsRequest request =
                requestRepository
                        .findByIdForUpdate(requestId)
                        .orElseThrow(OperationsRequestNotFoundException::new);

        request.reject(checkerId, note);

        log.info("Request {} rejected by {}", requestId, checkerId);

        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public List<OperationsRequestResponse> list(boolean pending) {

        List<OperationsRequest> requests =
                pending
                        ? requestRepository.findByStatusOrderByRequestedAtAsc(
                                OperationsRequestStatus.PENDING, PageRequest.of(0, LIST_SIZE))
                        : requestRepository.findByStatusNotOrderByDecidedAtDesc(
                                OperationsRequestStatus.PENDING, PageRequest.of(0, LIST_SIZE));

        return requests.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public OperationsRequestResponse get(UUID requestId) {

        return requestRepository
                .findById(requestId)
                .map(this::toResponse)
                .orElseThrow(OperationsRequestNotFoundException::new);
    }

    private OperationsRequestResponse toResponse(OperationsRequest request) {

        Payment payment = request.getPaymentId() == null ? null : paymentService.get(request.getPaymentId());

        UUID accountId = payment == null ? request.getAccountId() : payment.getAccountId();

        Optional<Account> account =
                accountId == null ? Optional.empty() : accountRepository.findById(accountId);

        StaffTransactionResponse transaction =
                request.getTransactionId() == null ? null : ledgerService.getAnyTransaction(request.getTransactionId());

        StaffTransactionResponse result =
                request.getResultTransactionId() == null
                        ? null
                        : ledgerService.getAnyTransaction(request.getResultTransactionId());

        BigDecimal amount;
        String currency;

        if (request.getType() == OperationsRequestType.ADJUSTMENT) {
            currency = request.getCurrency();
            amount = BigDecimal.valueOf(request.getAmountMinor(), Currency.of(currency).minorUnit());
        } else if (payment != null) {
            currency = payment.getCurrency();
            amount = payment.amount().toMajor();
        } else {
            currency = transaction.transaction().currency();
            amount = transaction.transaction().amount();
        }

        return new OperationsRequestResponse(
                request.getId(),
                request.getType(),
                request.getStatus(),
                accountId,
                account.map(Account::getAccountNumber).orElse(null),
                account.map(found -> found.getCustomer().getFirstName() + " " + found.getCustomer().getLastName())
                        .orElse(null),
                request.getDirection(),
                request.getTransactionId(),
                transaction == null ? null : transaction.transaction().reference(),
                transaction == null ? null : transaction.transaction().description(),
                amount,
                currency,
                request.getReason(),
                request.getCustomerDescription(),
                request.getRequestedBy(),
                nameOf(request.getRequestedBy()),
                request.getRequestedAt(),
                request.getDecidedBy(),
                nameOf(request.getDecidedBy()),
                request.getDecidedAt(),
                request.getDecisionNote(),
                request.getResultTransactionId(),
                result == null ? null : result.transaction().reference(),
                request.getPaymentId(),
                payment == null ? null : payment.getReference(),
                payment == null ? null : payment.getType(),
                request.getResolution()
        );
    }

    private String nameOf(UUID customerId) {

        if (customerId == null) {
            return null;
        }

        return customerRepository
                .findById(customerId)
                .map(customer -> customer.getFirstName() + " " + customer.getLastName())
                .orElse(null);
    }
}
