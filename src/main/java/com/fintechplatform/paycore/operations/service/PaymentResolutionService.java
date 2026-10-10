package com.fintechplatform.paycore.operations.service;

import com.fintechplatform.paycore.banktransfer.rail.RailStatus;
import com.fintechplatform.paycore.bills.provider.BillProvider;
import com.fintechplatform.paycore.bills.service.BillPaymentService;
import com.fintechplatform.paycore.banktransfer.service.BankTransferService;
import com.fintechplatform.paycore.common.persistence.ConstraintViolations;
import com.fintechplatform.paycore.funding.provider.FundingStatus;
import com.fintechplatform.paycore.funding.service.FundingService;
import com.fintechplatform.paycore.ledger.dto.response.StaffTransactionResponse;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.operations.dto.PaymentResolutionRequestBody;
import com.fintechplatform.paycore.operations.entity.OperationsRequest;
import com.fintechplatform.paycore.operations.exception.DuplicatePaymentResolutionException;
import com.fintechplatform.paycore.operations.exception.ResolutionContradictsProviderException;
import com.fintechplatform.paycore.operations.repository.OperationsRequestRepository;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentResolution;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.exception.InvalidPaymentTransitionException;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentResolutionService {

    static final String PENDING_RESOLUTION_CONSTRAINT = "uk_operations_requests_pending_resolution";

    static final String TRANSFER_RETURNED = "The transfer did not complete; your money was returned";
    static final String NOT_CHARGED = "The payment did not complete; you were not charged";

    private static final Logger log = LoggerFactory.getLogger(PaymentResolutionService.class);

    private final OperationsRequestRepository requestRepository;
    private final PaymentService paymentService;
    private final BankTransferService bankTransferService;
    private final FundingService fundingService;
    private final LedgerService ledgerService;
    private final BillPaymentService billPaymentService;

    public PaymentResolutionService(
            OperationsRequestRepository requestRepository,
            PaymentService paymentService,
            BankTransferService bankTransferService,
            FundingService fundingService,
            LedgerService ledgerService,
            BillPaymentService billPaymentService
    ) {
        this.billPaymentService = billPaymentService;
        this.requestRepository = requestRepository;
        this.paymentService = paymentService;
        this.bankTransferService = bankTransferService;
        this.fundingService = fundingService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public OperationsRequest request(UUID makerId, PaymentResolutionRequestBody body) {

        Payment payment = paymentService.get(body.paymentId());

        requireResolvable(payment, body.resolution());
        requireNotOwnPayment(payment, makerId);
        requireNoContradiction(payment, body.resolution());

        try {
            OperationsRequest request =
                    requestRepository.saveAndFlush(
                            OperationsRequest.paymentResolution(
                                    makerId, payment.getId(), body.resolution(), body.reason(), body.customerDescription()
                            )
                    );

            log.info("Resolution {} of payment {} requested by {}", body.resolution(), payment.getReference(), makerId);

            return request;

        } catch (DataIntegrityViolationException exception) {

            if (ConstraintViolations.violates(exception, PENDING_RESOLUTION_CONSTRAINT)) {
                throw new DuplicatePaymentResolutionException();
            }

            throw exception;
        }
    }

    @Transactional
    public Optional<UUID> apply(OperationsRequest request, UUID checkerId) {

        PaymentResolution resolution = request.getResolution();

        Payment payment = paymentService.lockForResolution(request.getPaymentId());

        requireResolvable(payment, resolution);
        requireNotOwnPayment(payment, request.getRequestedBy());
        requireNotOwnPayment(payment, checkerId);

        Optional<String> creditReference = requireNoContradiction(payment, resolution);

        Optional<UUID> posted = switch (resolution) {

            case COMPLETE -> {
                paymentService.succeed(payment.getId());
                yield Optional.empty();
            }

            case REVERSE -> {
                StaffTransactionResponse reversal = ledgerService.reverse(
                        request.getRequestedBy(),
                        checkerId,
                        payment.getLedgerTransactionId(),
                        request.getReason(),
                        request.getCustomerDescription(),
                        request.ledgerKey()
                );
                paymentService.failReversed(payment.getId(), TRANSFER_RETURNED, reversal.transaction().id());
                yield Optional.of(reversal.transaction().id());
            }

            case CREDIT -> {
                Payment credited = paymentService.completeFunding(
                        payment.getId(), fundingService.terms(), creditReference.orElseThrow()
                );
                yield Optional.of(credited.getLedgerTransactionId());
            }

            case REFUND -> {
                paymentService.refunded(payment.getId(), fundingService.refund(payment), FundingService.REFUNDED_TO_CARD);
                yield Optional.empty();
            }

            case CLOSE -> {
                paymentService.failFunding(payment.getId(), NOT_CHARGED, null);
                yield Optional.empty();
            }
        };

        log.info("Payment {} resolved {} (request {}, approved by {})",
                payment.getReference(), resolution, request.getId(), checkerId);

        return posted;
    }

    private static void requireResolvable(Payment payment, PaymentResolution resolution) {

        if (payment.getStatus() != PaymentStatus.NEEDS_REVIEW) {
            throw new InvalidPaymentTransitionException(
                    payment.getReference(), payment.getStatus(),
                    resolution.succeeds() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED
            );
        }

        if (!PaymentResolution.allowedFor(payment.getType()).contains(resolution)) {
            throw new IllegalArgumentException(
                    resolution + " does not apply to a " + payment.getType()
                            + "; use one of " + PaymentResolution.allowedFor(payment.getType())
            );
        }
    }

    private static void requireNotOwnPayment(Payment payment, UUID officerId) {

        if (payment.getCustomerId().equals(officerId)) {
            throw new AccessDeniedException("Staff cannot resolve their own payments");
        }
    }

    private Optional<String> requireNoContradiction(Payment payment, PaymentResolution resolution) {

        if (payment.isBillPayment()) {

            BillProvider.Result status = billPaymentService.currentStatus(payment);
            BillProvider.Result.Outcome outcome =
                    status == null ? BillProvider.Result.Outcome.PENDING : status.outcome();

            boolean contradicts = switch (resolution) {
                case COMPLETE -> outcome == BillProvider.Result.Outcome.FAILED
                        || outcome == BillProvider.Result.Outcome.NOT_FOUND;
                case REVERSE -> outcome == BillProvider.Result.Outcome.DELIVERED;
                default -> false;
            };

            if (contradicts) {
                throw new ResolutionContradictsProviderException(resolution, outcome.name());
            }

            return Optional.empty();
        }

        if (payment.isOutboundTransfer()) {

            RailStatus status = bankTransferService.currentStatus(payment);
            RailStatus.Outcome outcome = status == null ? RailStatus.Outcome.PENDING : status.outcome();

            boolean contradicts = switch (resolution) {
                case COMPLETE -> outcome == RailStatus.Outcome.FAILED || outcome == RailStatus.Outcome.NOT_FOUND;
                case REVERSE -> outcome == RailStatus.Outcome.SUCCESSFUL;
                default -> false;
            };

            if (contradicts) {
                throw new ResolutionContradictsProviderException(resolution, outcome.name());
            }

            return Optional.empty();
        }

        FundingStatus status = fundingService.currentStatus(payment);
        FundingStatus.Outcome outcome = status == null ? FundingStatus.Outcome.PENDING : status.outcome();

        boolean contradicts = switch (resolution) {
            case CREDIT -> outcome == FundingStatus.Outcome.DECLINED
                    || outcome == FundingStatus.Outcome.NOT_FOUND
                    || outcome == FundingStatus.Outcome.REFUNDED;
            case REFUND -> outcome == FundingStatus.Outcome.DECLINED || outcome == FundingStatus.Outcome.NOT_FOUND;
            case CLOSE -> outcome == FundingStatus.Outcome.CONFIRMED || outcome == FundingStatus.Outcome.REFUNDED;
            default -> false;
        };

        if (contradicts) {
            throw new ResolutionContradictsProviderException(resolution, outcome.name());
        }

        if (resolution != PaymentResolution.CREDIT) {
            return Optional.empty();
        }

        if (outcome == FundingStatus.Outcome.CONFIRMED && status.providerReference() != null) {
            return Optional.of(status.providerReference());
        }

        return Optional.of(
                payment.getProviderReference() != null ? payment.getProviderReference() : payment.getExternalReference()
        );
    }
}
