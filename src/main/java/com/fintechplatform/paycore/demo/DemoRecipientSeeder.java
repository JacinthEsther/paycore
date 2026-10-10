package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoRecipientSeeder {

    public static final String EMAIL = "recipient@paycore.demo";
    public static final String CURRENCY = DemoCustomers.CURRENCY;

    private static final String SEED_REASON = "Developer Preview demo recipient";

    private static final Logger log = LoggerFactory.getLogger(DemoRecipientSeeder.class);

    private final CustomerRepository customerRepository;
    private final DemoCustomers customers;

    public DemoRecipientSeeder(
            CustomerRepository customerRepository,
            DemoCustomers customers
    ) {
        this.customerRepository = customerRepository;
        this.customers = customers;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    @Transactional
    public void seed() {

        Customer compliance =
                customerRepository
                        .findByEmail(DemoStaff.COMPLIANCE.email())
                        .orElseThrow(() -> new IllegalStateException("The demo staff must be seeded first"));

        Customer recipient =
                customers.findOrRegister("Tunde", "Bakare", EMAIL, "08030000001", SEED_REASON);

        customers.verifyKyc(recipient, 3);

        Account account =
                customers.ensureActive(customers.openAccount(recipient), compliance.getId(), SEED_REASON);

        log.info("Demo recipient ready: account {}", account.getAccountNumber());
    }

    @Transactional(readOnly = true)
    public Optional<Account> findAccount() {

        return customerRepository
                .findByEmail(EMAIL)
                .flatMap(recipient -> customers.findOpenAccount(recipient.getId()));
    }
}
