package com.fintechplatform.paycore.funding.service;

import com.fintechplatform.paycore.funding.config.FundingProperties;
import com.fintechplatform.paycore.funding.exception.FundingDeclinedException;
import com.fintechplatform.paycore.funding.exception.FundingDisabledException;
import com.fintechplatform.paycore.funding.exception.FundingProviderException;
import com.fintechplatform.paycore.funding.provider.FundingCollection;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.funding.provider.FundingResult;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.dto.response.FundingAllowanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.service.FundingTerms;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Customers adding money to their own accounts through the payment
 * provider. The customer only asks; the money is credited because the
 * provider confirmed the payment, never because the customer said so.
 *
 * <pre>
 * 1. ledger   a retry of a top-up that already posted? return it, charge nothing
 *             otherwise: own account, active, right currency, within limit?
 * 2. provider take the payment (no database transaction is open meanwhile)
 * 3. ledger   credit it: DEBIT settlement, CREDIT customer, checked again
 *             under the account lock
 * </pre>
 *
 * Not transactional itself: each ledger step is its own transaction, so no
 * row lock is held while the provider answers.
 */
@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final ObjectProvider<FundingProvider> providers;
    private final LedgerService ledgerService;
    private final FundingProperties properties;

    public FundingService(
            ObjectProvider<FundingProvider> providers,
            LedgerService ledgerService,
            FundingProperties properties
    ) {
        this.providers = providers;
        this.ledgerService = ledgerService;
        this.properties = properties;
    }

    public TransactionResponse fund(UUID customerId, UUID accountId, FundAccountRequest request) {

        FundingProvider provider = providers.getIfAvailable();

        if (provider == null) {
            throw new FundingDisabledException();
        }

        FundingTerms terms = terms(provider);

        Optional<TransactionResponse> previous = ledgerService.checkFunding(customerId, accountId, request, terms);

        if (previous.isPresent()) {
            return previous.get();
        }

        // Valid by now: checkFunding parsed it.
        Money amount = Money.ofMajor(request.amount(), Currency.of(request.currency()));

        FundingResult result = collect(provider, customerId, accountId, amount, request.idempotencyKey().trim());

        if (!result.confirmed()) {
            log.info(
                    "Top-up of {} {} to account {} declined by {} ({}): {}",
                    amount.toMajor(), amount.currency().code(), accountId,
                    provider.providerName(), result.providerReference(), result.reason()
            );
            throw new FundingDeclinedException(result.reason(), result.providerReference());
        }

        TransactionResponse posted;

        try {
            posted = ledgerService.postFunding(customerId, accountId, request, terms, result.providerReference());
        } catch (RuntimeException exception) {
            // The account changed between the check and the credit (frozen,
            // limit reached by a concurrent top-up). The customer has paid,
            // so this must not disappear into a 4xx: someone has to refund
            // the payment or credit it by hand.
            log.error(
                    "Payment {} of {} {} confirmed by {} but NOT credited to account {}: {}. Refund or credit it manually.",
                    result.providerReference(), amount.toMajor(), amount.currency().code(),
                    provider.providerName(), accountId, exception.getMessage()
            );
            throw exception;
        }

        if (!result.providerReference().equals(posted.providerReference())) {
            // A concurrent duplicate of this request was credited first, with
            // its own payment; this second payment was not credited.
            log.error(
                    "Payment {} confirmed by {} duplicates top-up {} and was NOT credited. Refund it.",
                    result.providerReference(), provider.providerName(), posted.reference()
            );
        }

        return posted;
    }

    public FundingAllowanceResponse allowance(UUID customerId, UUID accountId) {

        FundingProvider provider = providers.getIfAvailable();

        return ledgerService.getFundingAllowance(
                customerId,
                accountId,
                provider == null ? null : provider.providerName(),
                properties.getMaxTotalPerAccount()
        );
    }

    private FundingTerms terms(FundingProvider provider) {
        return new FundingTerms(provider.providerName(), provider.narration(), properties.getMaxTotalPerAccount());
    }

    private FundingResult collect(
            FundingProvider provider,
            UUID customerId,
            UUID accountId,
            Money amount,
            String idempotencyKey
    ) {

        FundingCollection collection =
                new FundingCollection(customerId, accountId, amount, merchantReference(customerId, idempotencyKey));

        FundingResult result;

        try {
            result = provider.collect(collection);
        } catch (FundingProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FundingProviderException(provider.providerName() + " failed to answer", exception);
        }

        if (result == null || result.providerReference() == null || result.providerReference().isBlank()) {
            throw new FundingProviderException(provider.providerName() + " returned no payment reference", null);
        }

        return result;
    }

    /**
     * PC-<32 hex>: stable for one customer's idempotency key, so a provider
     * that deduplicates by merchant reference sees a retry as the same
     * payment. Hashed so the key itself never leaves PayCore.
     */
    static String merchantReference(UUID customerId, String idempotencyKey) {

        try {
            byte[] digest =
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest((customerId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));

            return "PC-" + HexFormat.of().withUpperCase().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
