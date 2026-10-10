package com.fintechplatform.paycore.funding.provider;

import com.fintechplatform.paycore.funding.exception.FundingProviderException;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedFundingProviderTest {

    private final CardPaymentRecords records = new InMemoryCardPaymentRecords();
    private final SimulatedFundingProvider provider = new SimulatedFundingProvider(records);

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
        assertThat(provider.collect(collection("1000.95")).confirmed()).isTrue();
    }

    @Test
    void everyPaymentShouldGetItsOwnReference() {

        assertThat(provider.collect(collection("10")).providerReference())
                .isNotEqualTo(provider.collect(collection("10")).providerReference());
    }

    @Test
    void askingAgainForTheSamePaymentShouldReturnTheFirstAnswer() {

        FundingCollection collection = collection("10");

        assertThat(provider.collect(collection)).isEqualTo(provider.collect(collection));
    }

    @Test
    void statusShouldReportWhatHappenedToAPayment() {

        FundingCollection confirmed = collection("10");
        FundingCollection declined = collection("10.99");
        String confirmedReference = provider.collect(confirmed).providerReference();
        provider.collect(declined);

        assertThat(provider.status(confirmed.merchantReference()))
                .isEqualTo(FundingStatus.confirmed(confirmedReference));
        assertThat(provider.status(declined.merchantReference()).outcome())
                .isEqualTo(FundingStatus.Outcome.DECLINED);
        assertThat(provider.status("PC-NEVER-SEEN").outcome())
                .isEqualTo(FundingStatus.Outcome.NOT_FOUND);
    }

    @Test
    void ninetyEightKoboShouldChargeButLoseTheAnswer() {

        FundingCollection collection = collection("10.98");

        assertThatThrownBy(() -> provider.collect(collection)).isInstanceOf(FundingProviderException.class);

        assertThat(provider.status(collection.merchantReference()).outcome())
                .isEqualTo(FundingStatus.Outcome.CONFIRMED);
    }

    @Test
    void ninetySevenKoboShouldNeverArrive() {

        FundingCollection collection = collection("10.97");

        assertThatThrownBy(() -> provider.collect(collection)).isInstanceOf(FundingProviderException.class);

        assertThat(provider.status(collection.merchantReference()).outcome())
                .isEqualTo(FundingStatus.Outcome.NOT_FOUND);
    }

    @Test
    void aRefundShouldHappenOnceAndShowInTheStatus() {

        FundingCollection collection = collection("10");
        String providerReference = provider.collect(collection).providerReference();

        String refund = provider.refund(collection.merchantReference());

        assertThat(refund).startsWith("SIMREF-");
        assertThat(provider.refund(collection.merchantReference())).isEqualTo(refund);
        assertThat(provider.status(collection.merchantReference()))
                .isEqualTo(FundingStatus.refunded(providerReference, refund));
    }

    @Test
    void declinedOrUnknownChargesCannotBeRefunded() {

        FundingCollection declined = collection("10.99");
        provider.collect(declined);

        assertThatThrownBy(() -> provider.refund(declined.merchantReference()))
                .isInstanceOf(FundingProviderException.class);
        assertThatThrownBy(() -> provider.refund("PC-NEVER-SEEN"))
                .isInstanceOf(FundingProviderException.class);
    }

    @Test
    void ninetySixKoboShouldChargeAndNeverAnswerAStatusQuery() {

        FundingCollection collection = collection("10.96");

        assertThatThrownBy(() -> provider.collect(collection)).isInstanceOf(FundingProviderException.class);
        assertThatThrownBy(() -> provider.status(collection.merchantReference()))
                .isInstanceOf(FundingProviderException.class);

        assertThat(provider.refund(collection.merchantReference())).startsWith("SIMREF-");
    }

    @Test
    void aRestartedSimulatorShouldRememberEarlierCharges() {

        FundingCollection collection = collection("10.98");
        assertThatThrownBy(() -> provider.collect(collection)).isInstanceOf(FundingProviderException.class);

        SimulatedFundingProvider restarted = new SimulatedFundingProvider(records);

        assertThat(restarted.status(collection.merchantReference()).outcome())
                .isEqualTo(FundingStatus.Outcome.CONFIRMED);
    }

    private static FundingCollection collection(String amount) {

        return new FundingCollection(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.ofMajor(new BigDecimal(amount), Currency.of("NGN")),
                "PC-" + UUID.randomUUID()
        );
    }
}
