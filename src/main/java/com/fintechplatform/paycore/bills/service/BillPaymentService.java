package com.fintechplatform.paycore.bills.service;

import com.fintechplatform.paycore.bills.catalog.BillCatalog;
import com.fintechplatform.paycore.bills.catalog.BillCategory;
import com.fintechplatform.paycore.bills.catalog.Biller;
import com.fintechplatform.paycore.bills.dto.BillPaymentRequest;
import com.fintechplatform.paycore.bills.dto.BillPaymentResponse;
import com.fintechplatform.paycore.bills.dto.BillProductResponse;
import com.fintechplatform.paycore.bills.dto.BillerResponse;
import com.fintechplatform.paycore.bills.dto.VerifyBillCustomerResponse;
import com.fintechplatform.paycore.bills.exception.BillCustomerNotFoundException;
import com.fintechplatform.paycore.bills.exception.BillPaymentsDisabledException;
import com.fintechplatform.paycore.bills.exception.InvalidBillPaymentException;
import com.fintechplatform.paycore.bills.exception.UnknownBillProductException;
import com.fintechplatform.paycore.bills.provider.BillProvider;
import com.fintechplatform.paycore.bills.provider.BillProvider.Product;
import com.fintechplatform.paycore.bills.provider.BillProvider.Result;
import com.fintechplatform.paycore.bills.provider.BillProviderException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.exception.IdempotencyConflictException;
import com.fintechplatform.paycore.ledger.service.BillDebit;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.payment.config.PaymentProperties;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.enums.PaymentType;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class BillPaymentService {

    static final String CURRENCY = "NGN";

    static final String NOT_CONFIRMED_YET =
            "We have not been able to confirm this payment with the biller yet. Our operations team is checking it.";

    static final String NEVER_ARRIVED = "The payment did not reach the biller; your money was returned";

    static final String NEVER_SENT = "The payment could not be sent; your money was returned";

    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    private final ObjectProvider<BillProvider> providers;
    private final PaymentService paymentService;
    private final LedgerService ledgerService;
    private final CustomerRepository customerRepository;
    private final PaymentProperties paymentProperties;

    public BillPaymentService(
            ObjectProvider<BillProvider> providers,
            PaymentService paymentService,
            LedgerService ledgerService,
            CustomerRepository customerRepository,
            PaymentProperties paymentProperties
    ) {
        this.providers = providers;
        this.paymentService = paymentService;
        this.ledgerService = ledgerService;
        this.customerRepository = customerRepository;
        this.paymentProperties = paymentProperties;
    }

    public List<BillerResponse> billers() {

        requireProvider();

        return BillCatalog.billers().stream()
                .map(biller -> BillerResponse.of(
                        biller,
                        biller.category() == BillCategory.ELECTRICITY ? BillCatalog.METER_TYPES : null
                ))
                .toList();
    }

    public List<BillProductResponse> products(String billerId) {

        Biller biller = BillCatalog.require(billerId);

        if (!biller.products()) {
            return List.of();
        }

        return providerProducts(requireProvider(), biller).stream()
                .map(product -> new BillProductResponse(product.code(), product.name(), product.amount()))
                .toList();
    }

    public VerifyBillCustomerResponse verify(String billerId, String productCode, String customerNumber) {

        Biller biller = BillCatalog.require(billerId);
        String number = requireNumber(biller, customerNumber);

        BillProvider.Customer customer = lookUp(requireProvider(), biller, meterTypeOrNull(biller, productCode), number);

        return new VerifyBillCustomerResponse(biller.id(), number, customer.name(), customer.address());
    }

    public BillPaymentResponse pay(UUID customerId, UUID sourceAccountId, BillPaymentRequest request) {

        BillProvider provider = requireProvider();

        Optional<Payment> previous = paymentService.findByKey(customerId, request.idempotencyKey());

        if (previous.isPresent()) {
            return toResponse(sameBill(previous.get(), sourceAccountId, request));
        }

        Biller biller = BillCatalog.require(request.billerId());
        String number = requireNumber(biller, request.customerNumber());

        String productCode;
        String productName;
        BigDecimal amount;

        if (biller.products()) {
            Product product = providerProducts(provider, biller).stream()
                    .filter(found -> found.code().equals(request.productCode()))
                    .findFirst()
                    .orElseThrow(() -> new UnknownBillProductException(String.valueOf(request.productCode())));

            productCode = product.code();
            productName = product.name();
            amount = product.amount();
        } else {
            productCode = biller.category() == BillCategory.ELECTRICITY ? meterType(request.productCode()) : null;
            productName = productCode;
            amount = requireAmount(biller, request.amount());
        }

        String payee =
                biller.verified()
                        ? lookUp(provider, biller, productCode, number).name()
                        : null;

        String description =
                biller.name()
                        + (productName == null ? "" : " " + productName)
                        + " · " + number
                        + (payee == null ? "" : " (" + payee + ")");

        String requestId = provider.newRequestId();

        Payment payment =
                paymentService.initiateBill(
                        customerId,
                        sourceAccountId,
                        new BillDebit(
                                amount,
                                CURRENCY,
                                request.idempotencyKey(),
                                truncate(description, 500),
                                provider.providerName(),
                                requestId,
                                new Counterparty(biller.name(), biller.category().label(), number)
                        ),
                        biller.id(),
                        productCode,
                        productName
                );

        if (!requestId.equals(payment.getExternalReference())) {
            return toResponse(payment);
        }

        payment = paymentService.markProcessing(payment.getId());

        Result result;

        try {
            result = provider.pay(new BillProvider.Purchase(
                    requestId, biller.id(), productCode, number, amount, phoneOf(customerId, biller, number)
            ));
        } catch (RuntimeException exception) {
            log.warn("Bill payment {} ({}) has an unknown outcome; checking later: {}",
                    payment.getReference(), requestId, exception.getMessage());
            return toResponse(paymentService.markUnknown(payment.getId()));
        }

        return toResponse(apply(payment, result, false));
    }

    public Payment settle(Payment payment) {
        Result result = currentStatus(payment);
        return apply(payment, result == null ? Result.pending() : result, true);
    }

    public Optional<Payment> abandonUnsent(UUID paymentId, Instant staleBefore) {
        return paymentService.failOutboundIfUnsent(paymentId, staleBefore, NEVER_SENT);
    }

    public Result currentStatus(Payment payment) {

        BillProvider provider = providers.getIfAvailable();

        if (provider == null || !provider.providerName().equals(payment.getProvider())) {
            log.warn("Cannot check bill payment {}: {} is not the configured aggregator",
                    payment.getReference(), payment.getProvider());
            return null;
        }

        try {
            return provider.status(payment.getExternalReference());
        } catch (RuntimeException exception) {
            log.warn("Requery of bill payment {} with {} failed: {}",
                    payment.getReference(), provider.providerName(), exception.getMessage());
            return null;
        }
    }

    private Payment apply(Payment payment, Result result, boolean checking) {

        return switch (result.outcome()) {

            case DELIVERED -> paymentService.succeed(payment.getId(), result.token());

            case FAILED -> paymentService.failOutbound(payment.getId(), Objects.requireNonNullElse(
                    result.reason(), "The biller could not complete this payment"));

            case NOT_FOUND -> payment.getCreatedAt().isBefore(Instant.now().minus(paymentProperties.getNotFoundGrace()))
                    ? paymentService.failOutbound(payment.getId(), NEVER_ARRIVED)
                    : pending(payment, checking);

            case PENDING -> pending(payment, checking);
        };
    }

    private Payment pending(Payment payment, boolean checking) {
        return checking
                ? paymentService.stillUnknown(payment.getId(), NOT_CONFIRMED_YET)
                : paymentService.markUnknown(payment.getId());
    }

    public BillPaymentResponse toResponse(Payment payment) {

        TransactionResponse debit =
                ledgerService.getTransaction(payment.getCustomerId(), payment.getLedgerTransactionId());

        String status = switch (payment.getStatus()) {
            case SUCCEEDED -> BillPaymentResponse.SUCCESSFUL;
            case FAILED -> BillPaymentResponse.FAILED;
            default -> BillPaymentResponse.PENDING;
        };

        String reversalReference =
                payment.getReversalTransactionId() == null
                        ? null
                        : ledgerService.getTransaction(payment.getCustomerId(), payment.getReversalTransactionId()).reference();

        return new BillPaymentResponse(
                debit,
                status,
                payment.getBillBillerId(),
                payment.getBillProductName(),
                payment.getCounterpartyAccountNumber(),
                payment.getBillToken(),
                payment.getStatus() == PaymentStatus.SUCCEEDED ? null : payment.getFailureReason(),
                reversalReference,
                payment.getId(),
                payment.getReference(),
                payment.getStatus()
        );
    }

    private static Payment sameBill(Payment payment, UUID sourceAccountId, BillPaymentRequest request) {

        boolean pricedByBiller = BillCatalog.find(request.billerId()).map(Biller::products).orElse(false);

        boolean same =
                payment.getType() == PaymentType.BILL_PAYMENT
                        && payment.getAccountId().equals(sourceAccountId)
                        && payment.getBillBillerId().equals(request.billerId())
                        && payment.getCounterpartyAccountNumber().equals(request.customerNumber().replaceAll("\\s", ""))
                        && Objects.equals(payment.getBillProductCode(), blankToNull(request.productCode()))
                        && (pricedByBiller
                                || request.amount() != null
                                && payment.amount().equals(Money.ofMajor(request.amount(), Currency.of(CURRENCY))));

        if (!same) {
            throw new IdempotencyConflictException();
        }

        return payment;
    }

    private BillProvider requireProvider() {

        BillProvider provider = providers.getIfAvailable();

        if (provider == null) {
            throw new BillPaymentsDisabledException();
        }

        return provider;
    }

    private static List<Product> providerProducts(BillProvider provider, Biller biller) {
        try {
            return provider.products(biller.id());
        } catch (BillProviderException exception) {
            log.warn("Could not load {} products: {}", biller.id(), exception.getMessage());
            throw exception;
        }
    }

    private static BillProvider.Customer lookUp(BillProvider provider, Biller biller, String productCode, String number) {

        if (!biller.verified()) {
            throw new InvalidBillPaymentException(biller.name() + " numbers are not verified");
        }

        return provider.verify(biller.id(), productCode, number).orElseThrow(BillCustomerNotFoundException::new);
    }

    private static String requireNumber(Biller biller, String customerNumber) {

        String number = customerNumber == null ? "" : customerNumber.replaceAll("\\s", "");

        if (!biller.category().acceptsNumber(number)) {
            throw new InvalidBillPaymentException("Enter a valid " + biller.category().numberLabel().toLowerCase());
        }

        return number;
    }

    private static BigDecimal requireAmount(Biller biller, BigDecimal amount) {

        if (amount == null) {
            throw new InvalidBillPaymentException("Amount is required");
        }

        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidBillPaymentException("Amount must have at most 2 decimal places");
        }

        if (amount.compareTo(biller.minAmount()) < 0 || amount.compareTo(biller.maxAmount()) > 0) {
            throw new InvalidBillPaymentException(
                    biller.name() + " payments must be between NGN " + biller.minAmount().toPlainString()
                            + " and NGN " + biller.maxAmount().toPlainString()
            );
        }

        return amount;
    }

    private static String meterTypeOrNull(Biller biller, String productCode) {
        return biller.category() == BillCategory.ELECTRICITY ? meterType(productCode) : null;
    }

    private static String meterType(String productCode) {
        if (!isMeterType(productCode)) {
            throw new InvalidBillPaymentException("Choose a prepaid or postpaid meter");
        }
        return productCode;
    }

    private static boolean isMeterType(String productCode) {
        return productCode != null && BillCatalog.METER_TYPES.contains(productCode);
    }

    private String phoneOf(UUID customerId, Biller biller, String number) {

        if (biller.category() == BillCategory.AIRTIME || biller.category() == BillCategory.DATA) {
            return number;
        }

        return customerRepository
                .findById(customerId)
                .map(customer -> customer.getPhoneNumber())
                .filter(Objects::nonNull)
                .map(phone -> phone.startsWith("+234") ? "0" + phone.substring(4) : phone)
                .orElse("08000000000");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
