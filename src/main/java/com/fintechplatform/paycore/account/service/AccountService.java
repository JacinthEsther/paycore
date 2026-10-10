package com.fintechplatform.paycore.account.service;

import com.fintechplatform.paycore.account.config.AccountProperties;
import com.fintechplatform.paycore.account.dto.request.OpenAccountRequest;
import com.fintechplatform.paycore.account.dto.response.AccountResponse;
import com.fintechplatform.paycore.account.dto.response.AccountStatusEventResponse;
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
import com.fintechplatform.paycore.common.persistence.ConstraintViolations;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class AccountService {

    static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

    static final String ACCOUNT_NUMBER_CONSTRAINT = "uk_accounts_account_number";
    static final String OPEN_ACCOUNT_CONSTRAINT = "uk_accounts_open_customer_type_currency";

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final AccountStatusEventRepository accountStatusEventRepository;
    private final CustomerRepository customerRepository;
    private final KycProfileRepository kycProfileRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountProperties accountProperties;
    private final TransactionTemplate transactionTemplate;

    public AccountService(
            AccountRepository accountRepository,
            AccountStatusEventRepository accountStatusEventRepository,
            CustomerRepository customerRepository,
            KycProfileRepository kycProfileRepository,
            AccountNumberGenerator accountNumberGenerator,
            AccountProperties accountProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.accountRepository = accountRepository;
        this.accountStatusEventRepository = accountStatusEventRepository;
        this.customerRepository = customerRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountProperties = accountProperties;
        this.transactionTemplate = transactionTemplate;
    }

    public AccountResponse openAccount(
            UUID customerId,
            OpenAccountRequest request
    ) {

        if (request.type().isSystem()) {
            throw new IllegalArgumentException("Customers cannot open " + request.type() + " accounts");
        }

        String currency = normalizeCurrency(request.currency());

        for (int attempt = 1; attempt <= MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {

            String accountNumber = accountNumberGenerator.generate();

            try {

                return transactionTemplate.execute(status ->
                        open(customerId, request.type(), currency, accountNumber)
                );

            } catch (DataIntegrityViolationException exception) {

                if (ConstraintViolations.violates(exception, ACCOUNT_NUMBER_CONSTRAINT)) {
                    log.warn("Account number collision on attempt {}", attempt);
                    continue;
                }

                if (ConstraintViolations.violates(exception, OPEN_ACCOUNT_CONSTRAINT)) {
                    throw new AccountAlreadyExistsException(request.type(), currency);
                }

                throw exception;
            }
        }

        log.error(
                "Could not allocate an account number after {} attempts",
                MAX_ACCOUNT_NUMBER_ATTEMPTS
        );

        throw new AccountNumberUnavailableException(MAX_ACCOUNT_NUMBER_ATTEMPTS);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getOwnAccounts(UUID customerId) {

        return accountRepository
                .findByCustomerIdOrderByCreatedAtAsc(customerId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getOwnAccount(UUID customerId, UUID accountId) {

        return accountRepository
                .findByIdAndCustomerId(accountId, customerId)
                .map(this::toResponse)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getCustomerAccounts(UUID customerId) {

        if (!customerRepository.existsById(customerId)) {
            throw new CustomerNotFoundException(customerId);
        }

        return getOwnAccounts(customerId);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {

        return toResponse(findAccount(accountId));
    }

    @Transactional(readOnly = true)
    public List<AccountStatusEventResponse> getHistory(UUID accountId) {

        findAccount(accountId);

        return accountStatusEventRepository
                .findByAccountIdOrderByOccurredAtAscIdAsc(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AccountResponse activate(UUID accountId, UUID staffId, String reason) {
        return changeStatus(accountId, staffId, reason, AccountEventType.ACTIVATED, account -> {
            ensureKycVerified(account.getCustomer());
            account.activate();
        });
    }

    @Transactional
    public AccountResponse freeze(UUID accountId, UUID staffId, String reason) {
        return changeStatus(accountId, staffId, reason, AccountEventType.FROZEN, Account::freeze);
    }

    @Transactional
    public AccountResponse unfreeze(UUID accountId, UUID staffId, String reason) {
        return changeStatus(accountId, staffId, reason, AccountEventType.UNFROZEN, Account::unfreeze);
    }

    @Transactional
    public AccountResponse close(UUID accountId, UUID staffId, String reason) {
        return changeStatus(accountId, staffId, reason, AccountEventType.CLOSED, Account::close);
    }

    private AccountResponse open(
            UUID customerId,
            AccountType type,
            String currency,
            String accountNumber
    ) {

        Customer customer = customerRepository.getReferenceById(customerId);

        Account account =
                accountRepository.saveAndFlush(
                        Account.open(customer, accountNumber, type, currency)
                );

        accountStatusEventRepository.save(
                AccountStatusEvent.opened(account, customerId)
        );

        log.info(
                "Opened {} {} account {} for customer {}",
                type,
                currency,
                account.getId(),
                customerId
        );

        return toResponse(account);
    }

    private AccountResponse changeStatus(
            UUID accountId,
            UUID staffId,
            String reason,
            AccountEventType eventType,
            Consumer<Account> transition
    ) {

        Account account = findAccount(accountId);

        if (account.isOwnedBy(staffId)) {
            throw new AccessDeniedException("Staff cannot change their own accounts");
        }

        AccountStatus fromStatus = account.getStatus();

        try {
            transition.accept(account);
        } catch (IllegalStateException exception) {
            throw new InvalidAccountStateException(exception.getMessage());
        }

        Account saved = accountRepository.save(account);

        accountStatusEventRepository.save(
                AccountStatusEvent.statusChanged(
                        saved,
                        eventType,
                        fromStatus,
                        staffId,
                        reason.trim()
                )
        );

        log.info(
                "Account {} {} -> {} by {}",
                accountId,
                fromStatus,
                saved.getStatus(),
                staffId
        );

        return toResponse(saved);
    }

    private void ensureKycVerified(Customer customer) {

        boolean verified =
                kycProfileRepository
                        .findByCustomer(customer)
                        .map(KycProfile::getStatus)
                        .filter(status -> status == KycStatus.VERIFIED)
                        .isPresent();

        if (!verified) {
            throw new KycVerificationRequiredException();
        }
    }

    private String normalizeCurrency(String currency) {

        String code = currency.trim().toUpperCase(Locale.ROOT);

        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException exception) {
            throw new UnsupportedCurrencyException(code);
        }

        if (!accountProperties.getSupportedCurrencies().contains(code)) {
            throw new UnsupportedCurrencyException(code);
        }

        return code;
    }

    private Account findAccount(UUID accountId) {

        return accountRepository
                .findById(accountId)
                .filter(account -> !account.isSystemAccount())
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private AccountResponse toResponse(Account account) {

        return new AccountResponse(
                account.getId(),
                account.getCustomer().getId(),
                account.getAccountNumber(),
                account.getType(),
                account.getStatus(),
                account.getCurrency(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                account.getClosedAt()
        );
    }

    private AccountStatusEventResponse toResponse(AccountStatusEvent event) {

        return new AccountStatusEventResponse(
                event.getId(),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getPerformedBy(),
                event.getReason(),
                event.getOccurredAt()
        );
    }
}
