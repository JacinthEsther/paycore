package com.fintechplatform.paycore.account.entity;

import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.customer.entity.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private final Customer customer = newCustomer();

    @Test
    void shouldOpenPendingAccount() {

        Account account = pendingAccount();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(account.getAccountNumber()).isEqualTo("1234567896");
        assertThat(account.getType()).isEqualTo(AccountType.PERSONAL);
        assertThat(account.getCurrency()).isEqualTo("NGN");
        assertThat(account.getCustomer()).isSameAs(customer);
        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isEqualTo(account.getCreatedAt());
        assertThat(account.getClosedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123456789", "12345678901", "12345678a0"})
    void shouldRejectMalformedAccountNumber(String accountNumber) {

        assertThatThrownBy(() ->
                Account.open(customer, accountNumber, AccountType.PERSONAL, "NGN")
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ngn", "NG", "NGNN", "₦"})
    void shouldRejectCurrencyThatIsNotAnUpperCaseCode(String currency) {

        assertThatThrownBy(() ->
                Account.open(customer, "1234567896", AccountType.PERSONAL, currency)
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotOpenSystemAccountForCustomer() {

        assertThatThrownBy(() ->
                Account.open(customer, "1234567896", AccountType.SETTLEMENT, "NGN")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system account");
    }

    @Test
    void customerAccountIsOwnedOnlyByItsCustomer() {

        Account account = pendingAccount();

        assertThat(account.isSystemAccount()).isFalse();
        assertThat(account.isOwnedBy(customer.getId())).isTrue();
        assertThat(account.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    // ------------------------------------------------------------
    // activate
    // ------------------------------------------------------------

    @Test
    void shouldActivatePendingAccount() {

        Account account = pendingAccount();

        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(account.getCreatedAt());
    }

    @Test
    void shouldRejectActivatingActiveAccount() {

        Account account = activeAccount();

        assertThatThrownBy(account::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only pending accounts can be activated");
    }

    @Test
    void shouldRejectActivatingFrozenOrClosedAccount() {

        Account frozen = activeAccount();
        frozen.freeze();

        Account closed = pendingAccount();
        closed.close();

        assertThatThrownBy(frozen::activate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(closed::activate).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------
    // freeze / unfreeze
    // ------------------------------------------------------------

    @Test
    void shouldFreezeActiveAccount() {

        Account account = activeAccount();

        account.freeze();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void shouldRejectFreezingPendingAccount() {

        Account account = pendingAccount();

        assertThatThrownBy(account::freeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active accounts can be frozen");
    }

    @Test
    void shouldRejectFreezingFrozenAccount() {

        Account account = activeAccount();
        account.freeze();

        assertThatThrownBy(account::freeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active accounts can be frozen");
    }

    @Test
    void shouldUnfreezeFrozenAccount() {

        Account account = activeAccount();
        account.freeze();

        account.unfreeze();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void shouldRejectUnfreezingActiveAccount() {

        Account account = activeAccount();

        assertThatThrownBy(account::unfreeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only frozen accounts can be unfrozen");
    }

    // ------------------------------------------------------------
    // close
    // ------------------------------------------------------------

    @Test
    void shouldClosePendingAccount() {

        Account account = pendingAccount();

        account.close();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isNotNull();
    }

    @Test
    void shouldCloseActiveAccount() {

        Account account = activeAccount();

        account.close();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isNotNull();
    }

    @Test
    void shouldCloseFrozenAccount() {

        Account account = activeAccount();
        account.freeze();

        account.close();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void closedAccountShouldBeFinal() {

        Account account = activeAccount();
        account.close();

        assertThatThrownBy(account::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account is already closed");
        assertThatThrownBy(account::activate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(account::freeze).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(account::unfreeze).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldKnowItsOwner() {

        Account account = pendingAccount();

        assertThat(account.isOwnedBy(customer.getId())).isTrue();
        assertThat(account.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    private Account pendingAccount() {
        return Account.open(customer, "1234567896", AccountType.PERSONAL, "NGN");
    }

    private Account activeAccount() {
        Account account = pendingAccount();
        account.activate();
        return account;
    }

    private static Customer newCustomer() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());

        return customer;
    }
}
