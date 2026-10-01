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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * A fixed customer with an active NGN account that Developer Preview
 * visitors can send money to; each visitor otherwise has only their own
 * account. It has no password, so nobody can sign in as it, and it is
 * not staff, so an admin can reverse a transfer into it.
 *
 * Created or repaired after every restart, after the demo admin (who is
 * recorded as having activated its account). Its account is opened
 * directly rather than through KYC review: it stands in for "someone at
 * another customer", not for a real applicant.
 */
@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoRecipientSeeder {

    public static final String EMAIL = "recipient@paycore.demo";
    public static final String CURRENCY = "NGN";

    private static final String FIRST_NAME = "Tunde";
    private static final String LAST_NAME = "Bakare";
    private static final String COUNTRY_CODE = "NG";
    private static final String PHONE_NUMBER = "08030000001";

    private static final String SEED_REASON = "Developer Preview demo recipient";

    private static final Logger log = LoggerFactory.getLogger(DemoRecipientSeeder.class);

    private final DemoProperties properties;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final AccountStatusEventRepository accountStatusEventRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final RoleAssignmentService roleAssignmentService;
    private final AuthorizationService authorizationService;
    private final PhoneNumberService phoneNumberService;
    private final ApplicationEventPublisher eventPublisher;

    public DemoRecipientSeeder(
            DemoProperties properties,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            AccountStatusEventRepository accountStatusEventRepository,
            AccountNumberGenerator accountNumberGenerator,
            RoleAssignmentService roleAssignmentService,
            AuthorizationService authorizationService,
            PhoneNumberService phoneNumberService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.properties = properties;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.accountStatusEventRepository = accountStatusEventRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.roleAssignmentService = roleAssignmentService;
        this.authorizationService = authorizationService;
        this.phoneNumberService = phoneNumberService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    @Transactional
    public void seed() {

        Customer admin =
                customerRepository
                        .findByEmail(properties.normalizedAdminEmail())
                        .orElseThrow(() -> new IllegalStateException("The demo admin must be seeded first"));

        Customer recipient =
                customerRepository
                        .findByEmail(EMAIL)
                        .orElseGet(this::createRecipient);

        if (!recipient.isEmailVerified()) {
            recipient.verifyEmail();
        }

        switch (recipient.getStatus()) {
            case PENDING_VERIFICATION -> recipient.activate();
            case SUSPENDED -> recipient.reactivate();
            case ACTIVE -> { }
            case CLOSED -> throw new IllegalStateException("The demo recipient is closed");
        }

        if (!authorizationService.hasRole(recipient, RoleName.CUSTOMER)) {
            roleAssignmentService.assignRole(recipient, RoleName.CUSTOMER, null, SEED_REASON);
        }

        customerRepository.save(recipient);

        Account account =
                openAccount(recipient)
                        .orElseGet(() -> open(recipient));

        ensureActive(account, admin);

        log.info("Demo recipient ready: account {}", account.getAccountNumber());
    }

    /**
     * The recipient's NGN account, if one is open, for the UI to name as
     * a transfer destination.
     */
    @Transactional(readOnly = true)
    public Optional<Account> findAccount() {

        return customerRepository
                .findByEmail(EMAIL)
                .flatMap(this::openAccount);
    }

    private Optional<Account> openAccount(Customer recipient) {

        return accountRepository
                .findByCustomerIdOrderByCreatedAtAsc(recipient.getId())
                .stream()
                .filter(account -> account.getType() == AccountType.PERSONAL)
                .filter(account -> account.getCurrency().equals(CURRENCY))
                .filter(account -> account.getStatus() != AccountStatus.CLOSED)
                .findFirst();
    }

    private Customer createRecipient() {

        Customer recipient =
                customerRepository.save(
                        Customer.create(
                                FIRST_NAME,
                                LAST_NAME,
                                EMAIL,
                                phoneNumberService.normalize(PHONE_NUMBER, COUNTRY_CODE)
                        )
                );

        // Same side effects as a normal registration (its KYC profile).
        eventPublisher.publishEvent(new CustomerRegisteredEvent(recipient.getId()));

        return recipient;
    }

    private Account open(Customer recipient) {

        // Skips numbers already in use; the unique constraint still has the
        // final say, and startup runs one seeder at a time.
        String accountNumber = accountNumberGenerator.generate();

        while (accountRepository.findByAccountNumber(accountNumber).isPresent()) {
            accountNumber = accountNumberGenerator.generate();
        }

        Account account =
                accountRepository.saveAndFlush(
                        Account.open(recipient, accountNumber, AccountType.PERSONAL, CURRENCY)
                );

        accountStatusEventRepository.save(AccountStatusEvent.opened(account, recipient.getId()));

        return account;
    }

    /**
     * PENDING or FROZEN -> ACTIVE, audited as done by the demo admin.
     */
    private void ensureActive(Account account, Customer admin) {

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
            return;
        }

        Account saved = accountRepository.save(account);

        accountStatusEventRepository.save(
                AccountStatusEvent.statusChanged(saved, event, from, admin.getId(), SEED_REASON)
        );
    }
}
