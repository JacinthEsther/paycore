package com.fintechplatform.paycore.payment.service;

import com.fintechplatform.paycore.bills.service.BillPaymentService;
import com.fintechplatform.paycore.banktransfer.service.BankTransferService;
import com.fintechplatform.paycore.funding.service.FundingService;
import com.fintechplatform.paycore.payment.config.PaymentProperties;
import com.fintechplatform.paycore.payment.dto.PaymentResponse;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final BankTransferService bankTransferService;
    private final FundingService fundingService;
    private final BillPaymentService billPaymentService;
    private final PaymentProperties properties;

    public PaymentRecoveryService(
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            BankTransferService bankTransferService,
            FundingService fundingService,
            BillPaymentService billPaymentService,
            PaymentProperties properties
    ) {
        this.billPaymentService = billPaymentService;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.bankTransferService = bankTransferService;
        this.fundingService = fundingService;
        this.properties = properties;
    }

    public record Sweep(int abandoned, int markedUnknown, int checked) {
    }

    public Sweep recoverDue() {

        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.getStaleAfter());
        Limit batch = Limit.of(properties.getBatchSize());

        int abandoned = forEach(
                paymentRepository.findStale(List.of(PaymentStatus.CREATED), staleBefore, batch),
                id -> abandonUnsent(id, staleBefore)
        );

        int markedUnknown = forEach(
                paymentRepository.findStale(List.of(PaymentStatus.PROCESSING), staleBefore, batch),
                id -> paymentService.markUnknownIfStale(id, staleBefore)
        );

        int checked = forEach(
                paymentRepository.findDueStatusChecks(Instant.now(), batch),
                id -> paymentService.claimStatusCheck(id, false).map(this::settle)
        );

        if (abandoned + markedUnknown + checked > 0) {
            log.info("Payment recovery: {} unsent transfers returned, {} marked unknown, {} status checks",
                    abandoned, markedUnknown, checked);
        }

        return new Sweep(abandoned, markedUnknown, checked);
    }

    public PaymentResponse checkNow(UUID paymentId) {

        Optional<Payment> claimed = paymentService.claimStatusCheck(paymentId, true);

        return PaymentResponse.from(claimed.map(this::settle).orElseGet(() -> paymentService.get(paymentId)));
    }

    private Payment settle(Payment payment) {

        return switch (payment.getType()) {
            case OUTBOUND_TRANSFER -> bankTransferService.settle(payment);
            case CARD_FUNDING -> fundingService.settle(payment);
            case BILL_PAYMENT -> billPaymentService.settle(payment);
        };
    }

    private Optional<Payment> abandonUnsent(UUID paymentId, Instant staleBefore) {
        return paymentService.get(paymentId).isBillPayment()
                ? billPaymentService.abandonUnsent(paymentId, staleBefore)
                : bankTransferService.abandonUnsent(paymentId, staleBefore);
    }

    private static int forEach(List<UUID> paymentIds, Function<UUID, Optional<?>> action) {

        int done = 0;

        for (UUID paymentId : paymentIds) {
            try {
                if (action.apply(paymentId).isPresent()) {
                    done++;
                }
            } catch (RuntimeException exception) {
                log.error("Payment recovery failed for payment {}: {}", paymentId, exception.getMessage(), exception);
            }
        }

        return done;
    }
}
