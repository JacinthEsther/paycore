package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.AuthorizationService;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.customer.service.PhoneNumberService;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import com.fintechplatform.paycore.identity.repository.IdentityRepository;
import com.fintechplatform.paycore.identity.service.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Creates the shared demo staff once the application is ready, or repairs
 * them: after every restart the admin is ACTIVE with the CUSTOMER and
 * ADMIN roles, each operations officer ({@link DemoStaff}) is ACTIVE with
 * the OPERATIONS role, and all accept the configured password.
 */
@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoAdminSeeder {

    private static final Logger log =
            LoggerFactory.getLogger(DemoAdminSeeder.class);

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final String SEED_REASON = "Developer Preview demo admin";

    private final DemoProperties properties;
    private final CustomerRepository customerRepository;
    private final IdentityRepository identityRepository;
    private final IdentityService identityService;
    private final RoleAssignmentService roleAssignmentService;
    private final AuthorizationService authorizationService;
    private final PhoneNumberService phoneNumberService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public DemoAdminSeeder(
            DemoProperties properties,
            CustomerRepository customerRepository,
            IdentityRepository identityRepository,
            IdentityService identityService,
            RoleAssignmentService roleAssignmentService,
            AuthorizationService authorizationService,
            PhoneNumberService phoneNumberService,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher eventPublisher
    ) {
        this.properties = properties;
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.identityService = identityService;
        this.roleAssignmentService = roleAssignmentService;
        this.authorizationService = authorizationService;
        this.phoneNumberService = phoneNumberService;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;

        // Fail startup, not the first login, when the password is missing.
        String password = properties.getAdminPassword();

        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "paycore.demo.admin-password must be at least "
                            + MIN_PASSWORD_LENGTH + " characters: set the "
                            + "PAYCORE_DEMO_ADMIN_PASSWORD environment variable"
            );
        }
    }

    /** Runs before DemoRecipientSeeder, which records the admin as activating its account. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    @Transactional
    public void seed() {

        String email = properties.normalizedAdminEmail();

        Customer admin =
                customerRepository
                        .findByEmail(email)
                        .orElseGet(() -> createAdmin(email));

        activate(admin);

        ensureRole(admin, RoleName.CUSTOMER);
        ensureRole(admin, RoleName.ADMIN);

        ensurePassword(admin, properties.getAdminPassword());

        customerRepository.save(admin);

        log.info("Demo admin ready: {}", email);

        for (DemoStaff staff : DemoStaff.ALL) {
            seedOperationsOfficer(staff);
        }
    }

    /**
     * The same treatment for an operations officer: ACTIVE, the OPERATIONS
     * role only (no customer app, no admin powers), the demo password.
     */
    private void seedOperationsOfficer(DemoStaff staff) {

        // Not a registration: officers are employees, not customers, so they
        // get no KYC profile (and no place in the KYC review queue).
        Customer officer =
                customerRepository
                        .findByEmail(staff.email())
                        .orElseGet(() -> customerRepository.save(
                                Customer.create(
                                        staff.firstName(),
                                        staff.lastName(),
                                        staff.email(),
                                        phoneNumberService.normalize(
                                                staff.phoneNumber(),
                                                properties.getAdminCountryCode()
                                        )
                                )
                        ));

        activate(officer);
        ensureRole(officer, DemoStaff.ROLE);
        ensurePassword(officer, properties.getAdminPassword());

        customerRepository.save(officer);

        log.info("Demo operations officer ready: {}", staff.email());
    }

    private Customer createAdmin(String email) {

        String phoneNumber =
                phoneNumberService.normalize(
                        properties.getAdminPhoneNumber(),
                        properties.getAdminCountryCode()
                );

        Customer admin =
                customerRepository.save(
                        Customer.create(
                                properties.getAdminFirstName(),
                                properties.getAdminLastName(),
                                email,
                                phoneNumber
                        )
                );

        // Same side effects as a normal registration (its KYC profile).
        eventPublisher.publishEvent(new CustomerRegisteredEvent(admin.getId()));

        return admin;
    }

    private void activate(Customer staff) {

        if (!staff.isEmailVerified()) {
            staff.verifyEmail();
        }

        switch (staff.getStatus()) {
            case PENDING_VERIFICATION -> staff.activate();
            case SUSPENDED -> staff.reactivate();
            case ACTIVE -> { }
            case CLOSED -> throw new IllegalStateException(
                    "The demo staff account " + staff.getEmail() + " is closed"
            );
        }
    }

    private void ensureRole(Customer admin, String role) {

        if (!authorizationService.hasRole(admin, role)) {
            roleAssignmentService.assignRole(admin, role, null, SEED_REASON);
        }
    }

    private void ensurePassword(Customer admin, String password) {

        Optional<Identity> identity =
                identityRepository.findByCustomerAndProvider(
                        admin,
                        IdentityProvider.PASSWORD
                );

        if (identity.isEmpty()) {
            identityService.createPasswordIdentity(
                    admin,
                    passwordEncoder.encode(password)
            );
            return;
        }

        Identity existing = identity.get();

        if (!passwordEncoder.matches(password, existing.getPasswordHash())) {
            existing.changePasswordHash(passwordEncoder.encode(password));
        }

        if (!existing.isEnabled()) {
            existing.enable();
        }

        identityRepository.save(existing);
    }
}
