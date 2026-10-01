package com.fintechplatform.paycore.banktransfer.simulator;

import com.fintechplatform.paycore.banktransfer.rail.Bank;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.banktransfer.rail.NameEnquiry;
import com.fintechplatform.paycore.banktransfer.rail.RailPayment;
import com.fintechplatform.paycore.banktransfer.rail.RailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * In-process stand-in for an interbank rail provider, connected to one
 * pretend bank, {@link TestBank}. It never calls out and no real money
 * moves.
 *
 * <p>A payment of any amount ending in .99 is rejected by the beneficiary
 * bank, after the customer was debited, so the automatic reversal can be
 * seen as easily as a successful transfer. It must never be enabled where
 * real customers hold accounts.
 */
@Component
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class SimulatedBankRail implements BankRail {

    public static final String PROVIDER_NAME = "SIMULATED";

    /** The minor-unit ending the beneficiary bank always rejects. */
    public static final long REJECTED_MINOR_ENDING = 99;

    private static final Logger log = LoggerFactory.getLogger(SimulatedBankRail.class);

    private final TestBank testBank;

    public SimulatedBankRail(TestBank testBank) {
        this.testBank = testBank;

        log.warn(
                "Bank rail is SIMULATED: transfers to and from other banks are not real. "
                        + "Never use this with real customers."
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<Bank> banks() {
        return List.of(TestBank.BANK);
    }

    @Override
    public Optional<String> nameEnquiry(NameEnquiry enquiry) {

        if (!TestBank.BANK.code().equals(enquiry.bankCode())) {
            return Optional.empty();
        }

        return testBank.holderOf(enquiry.accountNumber(), enquiry.requestedBy());
    }

    @Override
    public RailResult send(RailPayment payment) {

        if (payment.amount().amountMinor() % 100 == REJECTED_MINOR_ENDING) {
            return RailResult.rejected(
                    "Beneficiary bank rejected the transfer (simulated: amounts ending in .99 always fail)"
            );
        }

        return RailResult.success();
    }
}
