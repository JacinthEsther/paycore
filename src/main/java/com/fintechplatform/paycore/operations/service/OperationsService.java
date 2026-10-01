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
import com.fintechplatform.paycore.operations.dto.OperationsRequestResponse;
import com.fintechplatform.paycore.operations.dto.ReversalRequestBody;
import com.fintechplatform.paycore.operations.entity.OperationsRequest;
import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;
import com.fintechplatform.paycore.operations.enums.OperationsRequestType;
import com.fintechplatform.paycore.operations.exception.DuplicateReversalRequestException;
import com.fintechplatform.paycore.operations.exception.OperationsRequestNotFoundException;
import com.fintechplatform.paycore.operations.repository.OperationsRequestRepository;
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

/**
 * Maker-checker for every money correction staff can make. An operations
 * officer (maker) files a request with a reason; nothing moves. A
 * different officer (checker) approves it, which posts the ledger
 * transaction recording both of them, or rejects it with a note.
 *
 * The rules a bank would apply, enforced here and in the database:
 * <ul>
 *   <li>the checker is never the maker (four eyes)</li>
 *   <li>neither may hold an account the correction touches</li>
 *   <li>a request is decided once; approving twice posts once</li>
 *   <li>at most one reversal of a transaction waits at a time</li>
 * </ul>
 */
@Service
public class OperationsService {

    static final String PENDING_REVERSAL_CONSTRAINT = "uk_operations_requests_pending_reversal";

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);

    private static final int LIST_SIZE = 100;

    private final OperationsRequestRepository requestRepository;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public OperationsService(
            OperationsRequestRepository requestRepository,
            LedgerService ledgerService,
            AccountRepository accountRepository,
            CustomerRepository customerRepository
    ) {
        this.requestRepository = requestRepository;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    // ============================================================
    // MAKER
    // ============================================================

    @Transactional
    public OperationsRequestResponse requestAdjustment(UUID makerId, AdjustmentRequestBody body) {

        Money amount = Money.ofMajor(body.amount(), Currency.of(body.currency()));

        // Fails now rather than at approval if it could never be posted.
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

    // ============================================================
    // CHECKER
    // ============================================================

    /**
     * Posts the correction and marks the request approved, in one database
     * transaction: if the ledger refuses (the money is gone, the account
     * closed), nothing is posted and the request stays pending.
     */
    @Transactional
    public OperationsRequestResponse approve(UUID checkerId, UUID requestId, String note) {

        OperationsRequest request =
                requestRepository
                        .findByIdForUpdate(requestId)
                        .orElseThrow(OperationsRequestNotFoundException::new);

        request.requireDecidableBy(checkerId);

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

    // ============================================================
    // READS
    // ============================================================

    /** Pending requests oldest first, or decided ones newest first. */
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

        Optional<Account> account =
                request.getAccountId() == null ? Optional.empty() : accountRepository.findById(request.getAccountId());

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
        } else {
            currency = transaction.transaction().currency();
            amount = transaction.transaction().amount();
        }

        return new OperationsRequestResponse(
                request.getId(),
                request.getType(),
                request.getStatus(),
                request.getAccountId(),
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
                result == null ? null : result.transaction().reference()
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
