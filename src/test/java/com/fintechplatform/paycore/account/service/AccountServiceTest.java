package com.fintechplatform.paycore.account.service;

import com.fintechplatform.paycore.account.config.AccountProperties;
import com.fintechplatform.paycore.account.dto.request.OpenAccountRequest;
import com.fintechplatform.paycore.account.dto.response.AccountResponse;
import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.entity.AccountStatusEvent;
import com.fintechplatform.paycore.account.enums.AccountEventType;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.account.exception.AccountAlreadyExistsException;
import com.fintechplatform.paycore.account.exception.AccountNotFoundException;
import com.fintechplatform.paycore.account.exception.AccountNumberUnavailableException;
import com.fintechplatform.paycore.account.exception.InvalidAccountStateException;
import com.fintechplatform.paycore.account.exception.KycVerificationRequiredException;
import com.fintechplatform.paycore.account.exception.UnsupportedCurrencyException;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.account.repository.AccountStatusEventRepository;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountStatusEventRepository accountStatusEventRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private KycProfileRepository kycProfileRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private PlatformTransactionManager transactionManager;

    private AccountService accountService;

    private Customer customer;
    private UUID customerId;

    @BeforeEach
    void setUp() {

        accountService = new AccountService(
                accountRepository,
                accountStatusEventRepository,
                customerRepository,
                kycProfileRepository,
                accountNumberGenerator,
                new AccountProperties(),
                new TransactionTemplate(transactionManager)
        );

        customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );
        customerId = UUID.randomUUID();
        ReflectionTestUtils.setField(customer, "id", customerId);

        when(customerRepository.getReferenceById(customerId))
                .thenReturn(customer);

        givenKycStatus(KycStatus.VERIFIED);

        when(accountNumberGenerator.generate()).thenReturn("1234567896");

        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    // ============================================================
    // OPENING
    // ============================================================

    @Test
    void shouldOpenPendingAccountForCustomer() {

        AccountResponse response =
                accountService.openAccount(customerId, request("NGN"));

        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.accountNumber()).isEqualTo("1234567896");
        assertThat(response.type()).isEqualTo(AccountType.PERSONAL);
        assertThat(response.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(response.currency()).isEqualTo("NGN");

        ArgumentCaptor<AccountStatusEvent> event =
                ArgumentCaptor.forClass(AccountStatusEvent.class);
        verify(accountStatusEventRepository).save(event.capture());

        assertThat(event.getValue().getEventType()).isEqualTo(AccountEventType.OPENED);
        assertThat(event.getValue().getFromStatus()).isNull();
        assertThat(event.getValue().getToStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(event.getValue().getPerformedBy()).isEqualTo(customerId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ngn", " NGN ", "Ngn"})
    void shouldNormalizeCurrency(String currency) {

        AccountResponse response =
                accountService.openAccount(customerId, request(currency));

        assertThat(response.currency()).isEqualTo("NGN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "XYZ"})
    void shouldRejectUnsupportedOrUnknownCurrency(String currency) {

        assertThatThrownBy(() ->
                accountService.openAccount(customerId, request(currency))
        )
                .isInstanceOf(UnsupportedCurrencyException.class);

        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void openingShouldNotReadTheCustomerOrKyc() {

        accountService.openAccount(customerId, request("NGN"));

        // A reference only: the auth check has already vetted the customer
        // for this request, and KYC is checked at activation.
        verify(customerRepository).getReferenceById(customerId);
        verifyNoMoreInteractions(customerRepository);
        verifyNoInteractions(kycProfileRepository);
    }

    @Test
    void normalPathShouldBeOneAttemptWithOneInsert() {

        accountService.openAccount(customerId, request("NGN"));

        verify(accountNumberGenerator, times(1)).generate();
        verify(accountRepository, times(1)).saveAndFlush(any(Account.class));
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());

        // No "does it exist?" queries: the unique constraints decide.
        verifyNoMoreInteractions(accountRepository);
    }

    // ============================================================
    // ACCOUNT NUMBER COLLISIONS
    // ============================================================

    @Test
    void shouldRetryWithNewNumberAfterCollision() {

        when(accountNumberGenerator.generate())
                .thenReturn("1111111111", "2222222222");

        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(violationOf(AccountService.ACCOUNT_NUMBER_CONSTRAINT))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));

        AccountResponse response =
                accountService.openAccount(customerId, request("NGN"));

        assertThat(response.accountNumber()).isEqualTo("2222222222");

        // Each attempt had its own transaction; the failed one rolled back.
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void shouldGiveUpAfterBoundedAttempts() {

        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(violationOf(AccountService.ACCOUNT_NUMBER_CONSTRAINT));

        assertThatThrownBy(() ->
                accountService.openAccount(customerId, request("NGN"))
        )
                .isInstanceOf(AccountNumberUnavailableException.class);

        verify(accountNumberGenerator, times(AccountService.MAX_ACCOUNT_NUMBER_ATTEMPTS))
                .generate();
        verify(transactionManager, times(AccountService.MAX_ACCOUNT_NUMBER_ATTEMPTS))
                .rollback(any());
    }

    @Test
    void shouldRejectSecondOpenAccountOfSameTypeAndCurrency() {

        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(violationOf(AccountService.OPEN_ACCOUNT_CONSTRAINT));

        assertThatThrownBy(() ->
                accountService.openAccount(customerId, request("NGN"))
        )
                .isInstanceOf(AccountAlreadyExistsException.class);

        verify(accountNumberGenerator, times(1)).generate();
    }

    @Test
    void shouldRethrowOtherIntegrityViolations() {

        DataIntegrityViolationException other = violationOf("fk_accounts_customer");

        when(accountRepository.saveAndFlush(any(Account.class))).thenThrow(other);

        assertThatThrownBy(() ->
                accountService.openAccount(customerId, request("NGN"))
        )
                .isSameAs(other);
    }

    // ============================================================
    // READING
    // ============================================================

    @Test
    void shouldReturnOwnAccount() {

        Account account = withId(openAccountEntity());

        when(accountRepository.findByIdAndCustomerId(account.getId(), customerId))
                .thenReturn(Optional.of(account));

        assertThat(accountService.getOwnAccount(customerId, account.getId()).id())
                .isEqualTo(account.getId());
    }

    @Test
    void shouldNotRevealAnotherCustomersAccount() {

        UUID accountId = UUID.randomUUID();

        when(accountRepository.findByIdAndCustomerId(accountId, customerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getOwnAccount(customerId, accountId))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void shouldRejectListingAccountsOfUnknownCustomer() {

        UUID unknown = UUID.randomUUID();

        when(customerRepository.existsById(unknown)).thenReturn(false);

        assertThatThrownBy(() -> accountService.getCustomerAccounts(unknown))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    // ============================================================
    // ACTIVATION
    // ============================================================

    @Test
    void shouldActivatePendingAccountOfVerifiedCustomer() {

        Account account = givenStoredAccount(false);
        UUID adminId = UUID.randomUUID();

        AccountResponse response =
                accountService.activate(account.getId(), adminId, "KYC verified");

        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<AccountStatusEvent> event =
                ArgumentCaptor.forClass(AccountStatusEvent.class);
        verify(accountStatusEventRepository).save(event.capture());

        assertThat(event.getValue().getEventType()).isEqualTo(AccountEventType.ACTIVATED);
        assertThat(event.getValue().getFromStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(event.getValue().getToStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(event.getValue().getPerformedBy()).isEqualTo(adminId);
    }

    @ParameterizedTest
    @EnumSource(value = KycStatus.class, names = "VERIFIED", mode = EnumSource.Mode.EXCLUDE)
    void shouldNotActivateWithoutVerifiedKyc(KycStatus status) {

        Account account = givenStoredAccount(false);
        givenKycStatus(status);

        assertThatThrownBy(() ->
                accountService.activate(account.getId(), UUID.randomUUID(), "Try")
        )
                .isInstanceOf(KycVerificationRequiredException.class);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING);
        verify(accountRepository, never()).save(any());
        verify(accountStatusEventRepository, never()).save(any());
    }

    @Test
    void shouldNotActivateWithoutKycProfile() {

        Account account = givenStoredAccount(false);

        when(kycProfileRepository.findByCustomer(customer))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                accountService.activate(account.getId(), UUID.randomUUID(), "Try")
        )
                .isInstanceOf(KycVerificationRequiredException.class);
    }

    @Test
    void shouldNotActivateAccountThatIsNotPending() {

        Account account = givenStoredAccount(true);

        assertThatThrownBy(() ->
                accountService.activate(account.getId(), UUID.randomUUID(), "Again")
        )
                .isInstanceOf(InvalidAccountStateException.class)
                .hasMessage("Only pending accounts can be activated");
    }

    // ============================================================
    // STAFF STATUS CHANGES
    // ============================================================

    @Test
    void shouldFreezeAccountAndRecordWhoAndWhy() {

        Account account = givenStoredAccount();
        UUID adminId = UUID.randomUUID();

        AccountResponse response =
                accountService.freeze(account.getId(), adminId, "  Suspected fraud  ");

        assertThat(response.status()).isEqualTo(AccountStatus.FROZEN);

        ArgumentCaptor<AccountStatusEvent> event =
                ArgumentCaptor.forClass(AccountStatusEvent.class);
        verify(accountStatusEventRepository).save(event.capture());

        assertThat(event.getValue().getEventType()).isEqualTo(AccountEventType.FROZEN);
        assertThat(event.getValue().getFromStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(event.getValue().getToStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThat(event.getValue().getPerformedBy()).isEqualTo(adminId);
        assertThat(event.getValue().getReason()).isEqualTo("Suspected fraud");
    }

    @Test
    void shouldUnfreezeAndCloseAccount() {

        Account account = givenStoredAccount();
        UUID adminId = UUID.randomUUID();

        accountService.freeze(account.getId(), adminId, "Check");
        accountService.unfreeze(account.getId(), adminId, "Cleared");
        AccountResponse closed = accountService.close(account.getId(), adminId, "Requested");

        assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        verify(accountStatusEventRepository, times(3)).save(any(AccountStatusEvent.class));
    }

    @Test
    void shouldRejectInvalidTransitionWithoutRecordingIt() {

        Account account = givenStoredAccount();

        assertThatThrownBy(() ->
                accountService.unfreeze(account.getId(), UUID.randomUUID(), "Why")
        )
                .isInstanceOf(InvalidAccountStateException.class)
                .hasMessage("Only frozen accounts can be unfrozen");

        verify(accountRepository, never()).save(any());
        verify(accountStatusEventRepository, never()).save(any());
    }

    @Test
    void shouldNotLetStaffChangeTheirOwnAccount() {

        Account account = givenStoredAccount();

        assertThatThrownBy(() ->
                accountService.freeze(account.getId(), customerId, "Mine")
        )
                .isInstanceOf(AccessDeniedException.class);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountStatusEventRepository, never()).save(any());
    }

    @Test
    void shouldRejectStatusChangeOfUnknownAccount() {

        UUID accountId = UUID.randomUUID();

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                accountService.close(accountId, UUID.randomUUID(), "Gone")
        )
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void shouldReturnHistoryInOrder() {

        Account account = givenStoredAccount();

        when(accountStatusEventRepository.findByAccountIdOrderByOccurredAtAscIdAsc(account.getId()))
                .thenReturn(List.of(AccountStatusEvent.opened(account, customerId)));

        assertThat(accountService.getHistory(account.getId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(AccountEventType.OPENED);
                    assertThat(event.performedBy()).isEqualTo(customerId);
                });
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private Account givenStoredAccount() {
        return givenStoredAccount(true);
    }

    private Account givenStoredAccount(boolean active) {

        Account account = withId(openAccountEntity());

        if (active) {
            account.activate();
        }

        when(accountRepository.findById(account.getId()))
                .thenReturn(Optional.of(account));

        when(accountRepository.save(account)).thenReturn(account);

        return account;
    }

    private Account openAccountEntity() {
        return Account.open(customer, "1234567896", AccountType.PERSONAL, "NGN");
    }

    private void givenKycStatus(KycStatus status) {

        KycProfile profile = mock(KycProfile.class);
        when(profile.getStatus()).thenReturn(status);

        when(kycProfileRepository.findByCustomer(customer))
                .thenReturn(Optional.of(profile));
    }

    private static OpenAccountRequest request(String currency) {
        return new OpenAccountRequest(AccountType.PERSONAL, currency);
    }

    private static Account withId(Account account) {
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException(
                "violation",
                new ConstraintViolationException("violation", new SQLException(), constraint)
        );
    }
}
