package com.fintechplatform.paycore.funding.provider;

/**
 * The only payment-provider contract the domain knows: something that
 * takes a customer's payment into PayCore's settlement bank account and
 * says whether it succeeded. Implementations (the demo simulator today;
 * Paystack, Flutterwave, Monnify, ... later) translate vendor responses
 * into {@link FundingResult}. At most one is active, chosen by
 * {@code paycore.funding.provider}; with none, customers cannot top up.
 *
 * <p>The simulator answers at once. A real provider usually confirms
 * later, through a webhook; that webhook would end in the same ledger
 * posting, keyed by the provider's payment reference.
 */
public interface FundingProvider {

    /**
     * @throws com.fintechplatform.paycore.funding.exception.FundingProviderException
     *         when the provider cannot give an answer (outage, bad
     *         credentials), as opposed to a declined payment
     */
    FundingResult collect(FundingCollection collection);

    /** Stored on every deposit it confirms, e.g. "SIMULATED". */
    String providerName();

    /** What customers read on their statement for a top-up. */
    String narration();
}
