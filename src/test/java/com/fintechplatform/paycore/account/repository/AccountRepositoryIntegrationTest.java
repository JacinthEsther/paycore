package com.fintechplatform.paycore.account.repository;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.entity.AccountStatusEvent;
import com.fintechplatform.paycore.account.enums.AccountEventType;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database rules must hold on their own, without the service's checks,
 * because concurrent requests can pass application checks together.
 */
@Testcontainers
@SpringBootTest
class AccountRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountStatusEventRepository accountStatusEventRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Customer customer;

    @BeforeEach
    void setUp() {

        jdbcTemplate.execute("DELETE FROM account_status_events");
        jdbcTemplate.execute("DELETE FROM accounts");
        jdbcTemplate.execute("DELETE FROM customers");

        customer = newCustomer("esther@example.com", "+2348011111111");
    }

    @Test
    void shouldSaveAccountWithUuidV7Id() {

        Account saved = accountRepository.saveAndFlush(account(customer, "1234567896"));

        assertThat(saved.getId().version()).isEqualTo(7);
        assertThat(accountRepository.findById(saved.getId()))
                .get()
                .satisfies(found -> {
                    assertThat(found.getAccountNumber()).isEqualTo("1234567896");
                    assertThat(found.getStatus()).isEqualTo(AccountStatus.PENDING);
                    assertThat(found.getCurrency()).isEqualTo("NGN");
                });
    }

    @Test
    void shouldFindAccountByAccountNumber() {

        Account saved = accountRepository.saveAndFlush(account(customer, "1234567896"));

        assertThat(accountRepository.findByAccountNumber("1234567896"))
                .get()
                .extracting(Account::getId)
                .isEqualTo(saved.getId());

        assertThat(accountRepository.findByAccountNumber("0000000000")).isEmpty();
    }

    @Test
    void shouldFindAccountOnlyForItsOwner() {

        Account saved = accountRepository.saveAndFlush(account(customer, "1234567896"));
        Customer other = newCustomer("other@example.com", "+2348022222222");

        assertThat(accountRepository.findByIdAndCustomerId(saved.getId(), customer.getId()))
                .isPresent();

        assertThat(accountRepository.findByIdAndCustomerId(saved.getId(), other.getId()))
                .isEmpty();
    }

    @Test
    void shouldListOnlyTheCustomersAccounts() {

        Account first = accountRepository.saveAndFlush(account(customer, "1111111111"));
        first.close();
        accountRepository.saveAndFlush(first);

        Account second = accountRepository.saveAndFlush(account(customer, "2222222222"));

        Customer other = newCustomer("other@example.com", "+2348022222222");
        accountRepository.saveAndFlush(account(other, "3333333333"));

        assertThat(accountRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId()))
                .extracting(Account::getAccountNumber)
                .containsExactly(first.getAccountNumber(), second.getAccountNumber());
    }

    @Test
    void shouldEnforceUniqueAccountNumber() {

        accountRepository.saveAndFlush(account(customer, "1234567896"));
        Customer other = newCustomer("other@example.com", "+2348022222222");

        assertThatThrownBy(() ->
                accountRepository.saveAndFlush(account(other, "1234567896"))
        )
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_accounts_account_number");
    }

    @Test
    void shouldEnforceOneOpenAccountPerCustomerTypeAndCurrency() {

        accountRepository.saveAndFlush(account(customer, "1111111111"));

        assertThatThrownBy(() ->
                accountRepository.saveAndFlush(account(customer, "2222222222"))
        )
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_accounts_open_customer_type_currency");
    }

    @Test
    void shouldAllowNewAccountAfterPreviousOneWasClosed() {

        Account closed = accountRepository.saveAndFlush(account(customer, "1111111111"));
        closed.close();
        accountRepository.saveAndFlush(closed);

        Account reopened = accountRepository.saveAndFlush(account(customer, "2222222222"));

        assertThat(accountRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId()))
                .extracting(Account::getStatus)
                .containsExactly(AccountStatus.CLOSED, AccountStatus.PENDING);

        assertThat(reopened.getId()).isNotNull();
    }

    @Test
    void shouldEnforceCustomerForeignKey() {

        assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        INSERT INTO accounts (id, customer_id, account_number, account_type,
                                              status, currency, created_at, updated_at)
                        VALUES (?, ?, '1234567896', 'PERSONAL', 'ACTIVE', 'NGN', now(), now())
                        """, UUID.randomUUID(), UUID.randomUUID())
        )
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_accounts_customer");
    }

    @Test
    void shouldRejectMalformedValuesAtTheDatabase() {

        assertThatThrownBy(() -> insertRaw("12345", "ACTIVE", "NGN", false))
                .hasMessageContaining("ck_accounts_account_number");

        assertThatThrownBy(() -> insertRaw("1234567896", "DORMANT", "NGN", false))
                .hasMessageContaining("ck_accounts_status");

        assertThatThrownBy(() -> insertRaw("1234567896", "ACTIVE", "ngn", false))
                .hasMessageContaining("ck_accounts_currency");
    }

    @Test
    void shouldKeepClosedAtConsistentWithStatus() {

        assertThatThrownBy(() -> insertRaw("1234567896", "CLOSED", "NGN", false))
                .hasMessageContaining("ck_accounts_closed_at");

        assertThatThrownBy(() -> insertRaw("1234567896", "ACTIVE", "NGN", true))
                .hasMessageContaining("ck_accounts_closed_at");
    }

    @Test
    void shouldRejectStaleConcurrentUpdate() {

        UUID id = accountRepository.saveAndFlush(account(customer, "1234567896")).getId();

        Account first = accountRepository.findById(id).orElseThrow();
        Account second = accountRepository.findById(id).orElseThrow();

        first.activate();
        accountRepository.saveAndFlush(first);

        second.close();

        assertThatThrownBy(() -> accountRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(accountRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void shouldStoreStatusEventsInOrder() {

        Account account = accountRepository.saveAndFlush(account(customer, "1234567896"));

        accountStatusEventRepository.saveAndFlush(
                AccountStatusEvent.opened(account, customer.getId())
        );

        account.activate();
        accountRepository.saveAndFlush(account);

        accountStatusEventRepository.saveAndFlush(
                AccountStatusEvent.statusChanged(
                        account,
                        AccountEventType.ACTIVATED,
                        AccountStatus.PENDING,
                        customer.getId(),
                        "KYC verified"
                )
        );

        assertThat(accountStatusEventRepository
                .findByAccountIdOrderByOccurredAtAscIdAsc(account.getId()))
                .extracting(AccountStatusEvent::getToStatus)
                .containsExactly(AccountStatus.PENDING, AccountStatus.ACTIVE);
    }

    private void insertRaw(
            String accountNumber,
            String status,
            String currency,
            boolean closed
    ) {
        jdbcTemplate.update("""
                INSERT INTO accounts (id, customer_id, account_number, account_type,
                                      status, currency, created_at, updated_at, closed_at)
                VALUES (?, ?, ?, 'PERSONAL', ?, ?, now(), now(), CASE WHEN ? THEN now() END)
                """,
                UUID.randomUUID(), customer.getId(), accountNumber, status, currency, closed);
    }

    private Customer newCustomer(String email, String phone) {
        return customerRepository.saveAndFlush(
                Customer.create("Esther", "Agboniro", email, phone)
        );
    }

    private static Account account(Customer owner, String accountNumber) {
        return Account.open(owner, accountNumber, AccountType.PERSONAL, "NGN");
    }
}
