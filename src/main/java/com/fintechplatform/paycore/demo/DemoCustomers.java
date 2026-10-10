package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.entity.AccountStatusEvent;
import com.fintechplatform.paycore.account.enums.AccountEventType;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.account.repository.AccountStatusEventRepository;
import com.fintechplatform.paycore.account.service.AccountNumberGenerator;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.AuthorizationService;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.customer.service.PhoneNumberService;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoCustomers {

    public static final String CURRENCY = "NGN";

    private static final String COUNTRY_CODE = "NG";

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final AccountStatusEventRepository accountStatusEventRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final RoleAssignmentService roleAssignmentService;
    private final AuthorizationService authorizationService;
    private final PhoneNumberService phoneNumberService;
    private final ApplicationEventPublisher eventPublisher;
    private final KycProfileRepository kycProfileRepository;

    public DemoCustomers(
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            AccountStatusEventRepository accountStatusEventRepository,
            AccountNumberGenerator accountNumberGenerator,
            RoleAssignmentService roleAssignmentService,
            AuthorizationService authorizationService,
            PhoneNumberService phoneNumberService,
            ApplicationEventPublisher eventPublisher,
            KycProfileRepository kycProfileRepository
    ) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.accountStatusEventRepository = accountStatusEventRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.roleAssignmentService = roleAssignmentService;
        this.authorizationService = authorizationService;
        this.phoneNumberService = phoneNumberService;
        this.eventPublisher = eventPublisher;
        this.kycProfileRepository = kycProfileRepository;
    }

    @Transactional
    public Customer findOrRegister(String firstName, String lastName, String email, String phoneNumber, String reason) {

        Customer customer =
                customerRepository
                        .findByEmail(email)
                        .orElseGet(() -> register(firstName, lastName, email, phoneNumber));

        if (!customer.isEmailVerified()) {
            customer.verifyEmail();
        }

        switch (customer.getStatus()) {
            case PENDING_VERIFICATION -> customer.activate();
            case SUSPENDED -> customer.reactivate();
            case ACTIVE -> { }
            case CLOSED -> throw new IllegalStateException("The demo customer " + email + " is closed");
        }

        if (!authorizationService.hasRole(customer, RoleName.CUSTOMER)) {
            roleAssignmentService.assignRole(customer, RoleName.CUSTOMER, null, reason);
        }

        return customerRepository.save(customer);
    }

    @Transactional
    public void verifyKyc(Customer customer, int tier) {

        kycProfileRepository
                .findByCustomer(customer)
                .filter(profile -> !profile.isVerified() || profile.getTier() < tier)
                .ifPresent(profile -> {
                    profile.verifyForSeededAccount(tier);
                    kycProfileRepository.save(profile);
                });
    }

    @Transactional(readOnly = true)
    public Optional<Account> findOpenAccount(UUID customerId) {

        return accountRepository
                .findByCustomerIdOrderByCreatedAtAsc(customerId)
                .stream()
                .filter(account -> account.getType() == AccountType.PERSONAL)
                .filter(account -> account.getCurrency().equals(CURRENCY))
                .filter(account -> account.getStatus() != AccountStatus.CLOSED)
                .findFirst();
    }

    @Transactional
    public Account openAccount(Customer customer) {

        return findOpenAccount(customer.getId()).orElseGet(() -> {

            String accountNumber = accountNumberGenerator.generate();

            while (accountRepository.findByAccountNumber(accountNumber).isPresent()) {
                accountNumber = accountNumberGenerator.generate();
            }

            Account account =
                    accountRepository.saveAndFlush(
                            Account.open(customer, accountNumber, AccountType.PERSONAL, CURRENCY)
                    );

            accountStatusEventRepository.save(AccountStatusEvent.opened(account, customer.getId()));

            return account;
        });
    }

    @Transactional
    public Account ensureActive(Account account, UUID actorId, String reason) {

        AccountStatus from = account.getStatus();

        AccountEventType event =
                switch (from) {
                    case ACTIVE -> null;
                    case PENDING -> {
                        account.activate();
                        yield AccountEventType.ACTIVATED;
                    }
                    case FROZEN -> {
                        account.unfreeze();
                        yield AccountEventType.UNFROZEN;
                    }
                    case CLOSED -> throw new IllegalStateException("A closed account cannot be reactivated");
                };

        if (event == null) {
            return account;
        }

        Account saved = accountRepository.save(account);

        accountStatusEventRepository.save(
                AccountStatusEvent.statusChanged(saved, event, from, actorId, reason)
        );

        return saved;
    }

    public String phoneNumber(String local) {
        return phoneNumberService.normalize(local, COUNTRY_CODE);
    }

    private Customer register(String firstName, String lastName, String email, String phoneNumber) {

        Customer customer =
                customerRepository.save(
                        Customer.create(firstName, lastName, email, phoneNumber(phoneNumber))
                );

        eventPublisher.publishEvent(new CustomerRegisteredEvent(customer.getId()));

        return customer;
    }
}
