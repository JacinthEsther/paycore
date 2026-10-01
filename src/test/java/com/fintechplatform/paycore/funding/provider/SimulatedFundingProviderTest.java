package com.fintechplatform.paycore.funding.provider;

import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedFundingProviderTest {

    private final SimulatedFundingProvider provider = new SimulatedFundingProvider();

    @Test
    void shouldConfirmAnOrdinaryAmount() {

        FundingResult result = provider.collect(collection("5000.00"));

        assertThat(result.confirmed()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.providerReference()).startsWith("SIMPAY-");
        assertThat(provider.providerName()).isEqualTo("SIMULATED");
    }

    @Test
    void shouldDeclineAnAmountEndingInNinetyNineKobo() {

        FundingResult result = provider.collect(collection("1000.99"));

        assertThat(result.confirmed()).isFalse();
        assertThat(result.reason()).contains(".99");
        assertThat(result.providerReference()).startsWith("SIMPAY-");
    }

    @Test
    void shouldConfirmNinetyNineNairaAndOtherNearMisses() {

        assertThat(provider.collect(collection("99.00")).confirmed()).isTrue();
        assertThat(provider.collect(collection("0.09")).confirmed()).isTrue();
        assertThat(provider.collect(collection("1000.98")).confirmed()).isTrue();
    }

    @Test
    void everyPaymentShouldGetItsOwnReference() {

        assertThat(provider.collect(collection("10")).providerReference())
                .isNotEqualTo(provider.collect(collection("10")).providerReference());
    }

    private static FundingCollection collection(String amount) {

        return new FundingCollection(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.ofMajor(new BigDecimal(amount), Currency.of("NGN")),
                "PC-TEST"
        );
    }
}
