package com.fintechplatform.paycore.banktransfer.rail;

import java.util.List;
import java.util.Optional;

/**
 * The interbank payment rail (NIP in Nigeria) as PayCore sees it, through
 * a provider: which banks can be reached, who holds an account there
 * (name enquiry), and paying out to it. Money coming in arrives the other
 * way, as a notification to PayCore's webhook.
 *
 * At most one is active, chosen by {@code paycore.rails.provider}; with
 * none, customers can only move money inside PayCore. The simulator
 * stands in for a real provider until one is connected.
 */
public interface BankRail {

    /** Stored on every transfer it carries, e.g. "SIMULATED". */
    String providerName();

    /** Banks reachable through the rail. */
    List<Bank> banks();

    /**
     * The account holder's name at the other bank, empty if the bank has
     * no such account. Customers confirm the name before they pay.
     *
     * @throws BankRailException when the rail cannot answer
     */
    Optional<String> nameEnquiry(NameEnquiry enquiry);

    /**
     * Pays the beneficiary bank. A rejection is an answer (the money
     * must go back to the customer); an exception means the outcome is
     * unknown and must be confirmed with the provider before anything is
     * reversed.
     *
     * @throws BankRailException when the rail cannot be reached
     */
    RailResult send(RailPayment payment);
}
