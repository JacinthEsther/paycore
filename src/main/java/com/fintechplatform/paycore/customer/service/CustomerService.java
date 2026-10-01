package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.common.persistence.ConstraintViolations;
import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import com.fintechplatform.paycore.customer.dto.request.UpdateCustomerRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerPageResponse;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.dto.response.CustomerSummaryResponse;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.event.CustomerAccessRevokedEvent;
import com.fintechplatform.paycore.customer.event.CustomerEmailChangedEvent;
import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.exception.DuplicateCustomerException;
import com.fintechplatform.paycore.customer.exception.InvalidCustomerStateException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.service.IdentityService;
import com.fintechplatform.paycore.kyc.dto.PageInfo;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class CustomerService {

    static final String EMAIL_CONSTRAINT = "uk_customers_email";
    static final String PHONE_CONSTRAINT = "uk_customers_phone";

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNumberService phoneNumberService;
    private final IdentityService identityService;
    private final RoleAssignmentService roleAssignmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;


    public CustomerService(
            CustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            PhoneNumberService phoneNumberService,
            IdentityService identityService,
            RoleAssignmentService roleAssignmentService,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate
    ) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneNumberService = phoneNumberService;
        this.identityService = identityService;
        this.roleAssignmentService = roleAssignmentService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }


    /**
     * Registers a customer. Built for heavy sign-up traffic:
     *
     * - The password is hashed first, outside any transaction. BCrypt is
     *   deliberately slow CPU work, and inside a transaction it would keep
     *   a pooled database connection idle for the whole hash, capping
     *   registrations at pool size / hash time.
     * - Duplicate email or phone is detected by the unique constraints, not
     *   by "does it exist?" queries first: fewer round trips, and two
     *   concurrent sign-ups with the same email get a clean 409 instead of
     *   racing past the check.
     * - Everything else (customer, role, identity, KYC profile) is written
     *   in one short transaction.
     *
     * Not @Transactional: the transaction is opened explicitly after
     * hashing.
     */
    public CustomerResponse register(
            RegisterCustomerRequest request
    ) {

        String email = normalizeEmail(request.email());

        String countryCode =
                normalizeCountryCode(request.countryCode());

        String phoneNumber =
                phoneNumberService.normalize(
                        request.phoneNumber().trim(),
                        countryCode
                );

        String passwordHash =
                passwordEncoder.encode(request.password());

        try {

            return transactionTemplate.execute(status ->
                    createCustomer(request, email, phoneNumber, passwordHash)
            );

        } catch (DataIntegrityViolationException exception) {

            if (ConstraintViolations.violates(exception, EMAIL_CONSTRAINT)) {
                throw new DuplicateCustomerException("Email is already registered");
            }

            if (ConstraintViolations.violates(exception, PHONE_CONSTRAINT)) {
                throw new DuplicateCustomerException("Phone number is already registered");
            }

            throw exception;
        }
    }

    private CustomerResponse createCustomer(
            RegisterCustomerRequest request,
            String email,
            String phoneNumber,
            String passwordHash
    ) {

        Customer customer = Customer.create(
                request.firstName().trim(),
                request.lastName().trim(),
                email,
                phoneNumber
        );

        // Flush first so a duplicate email or phone fails here, before the
        // role, identity and KYC rows are written.
        Customer savedCustomer =
                customerRepository.saveAndFlush(customer);

        // Public registration always gets CUSTOMER; the client never
        // chooses a role. performedBy is null because the system assigns it.
        roleAssignmentService.assignRole(
                savedCustomer,
                RoleName.CUSTOMER,
                null,
                "Default role on registration"
        );

        identityService.createPasswordIdentity(
                savedCustomer,
                passwordHash
        );

        // Creates the KYC profile in this transaction.
        eventPublisher.publishEvent(
                new CustomerRegisteredEvent(savedCustomer.getId())
        );

        return toResponse(savedCustomer);
    }

    /**
     * A customer whose email an identity provider has already verified
     * (sign-up with Google): active straight away, with the CUSTOMER role
     * and a KYC profile, but no password and no phone number yet. The
     * caller adds the sign-in identity in the same transaction.
     */
    @Transactional
    public Customer createVerifiedCustomer(String firstName, String lastName, String email) {

        Customer customer = Customer.create(firstName, lastName, normalizeEmail(email), null);
        customer.verifyEmail();
        customer.activate();

        Customer savedCustomer = customerRepository.saveAndFlush(customer);

        roleAssignmentService.assignRole(
                savedCustomer,
                RoleName.CUSTOMER,
                null,
                "Default role on sign-up with Google"
        );

        eventPublisher.publishEvent(new CustomerRegisteredEvent(savedCustomer.getId()));

        return savedCustomer;
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(UUID customerId) {

        Customer customer =
                findCustomer(customerId);

        return toResponse(customer);
    }


    /**
     * Admin list, newest first. {@code search} matches email, first or
     * last name (case-insensitive, substring); blank lists everyone.
     */
    @Transactional(readOnly = true)
    public CustomerPageResponse listCustomers(
            String search,
            int page,
            int size
    ) {

        PageRequest pageRequest =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Order.desc("createdAt"),
                                Sort.Order.desc("id")
                        )
                );

        String term = search == null ? "" : search.trim();

        Page<Customer> customers =
                term.isEmpty()
                        ? customerRepository.findAll(pageRequest)
                        : customerRepository
                        .findByEmailContainingIgnoreCaseOrFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
                                term,
                                term,
                                term,
                                pageRequest
                        );

        return new CustomerPageResponse(
                customers.stream()
                        .map(customer -> new CustomerSummaryResponse(
                                customer.getId(),
                                customer.getFirstName(),
                                customer.getLastName(),
                                customer.getEmail(),
                                customer.getStatus(),
                                customer.getRoles()
                                        .stream()
                                        .map(Role::getName)
                                        .sorted()
                                        .toList(),
                                customer.getCreatedAt()
                        ))
                        .toList(),
                PageInfo.of(customers)
        );
    }


    @Transactional
    public CustomerResponse updateProfile(
            UUID customerId,
            UpdateCustomerRequest request
    ) {

        Customer customer =
                findCustomer(customerId);

        if (customer.getStatus() ==
                com.fintechplatform.paycore.customer.enums.CustomerStatus.CLOSED) {

            throw new InvalidCustomerStateException(
                    "Closed customers cannot update their profile"
            );
        }

        if (request.firstName() != null ||
                request.lastName() != null) {

            String firstName =
                    request.firstName() != null
                            ? request.firstName().trim()
                            : customer.getFirstName();

            String lastName =
                    request.lastName() != null
                            ? request.lastName().trim()
                            : customer.getLastName();

            customer.updateProfile(
                    firstName,
                    lastName
            );
        }

        if (request.email() != null) {

            String newEmail =
                    normalizeEmail(request.email());

            if (!newEmail.equals(customer.getEmail())) {

                if (customerRepository.existsByEmail(newEmail)) {
                    throw new DuplicateCustomerException(
                            "Email is already registered"
                    );
                }

                customer.changeEmail(newEmail);

                // The password identity's subject is the email; left as the
                // old address it would block anyone registering with it.
                identityService.changePasswordIdentitySubject(customer, newEmail);

                // Unverified again: a link goes to the new address.
                eventPublisher.publishEvent(new CustomerEmailChangedEvent(customer.getId()));
            }
        }

        boolean phoneProvided =
                request.phoneNumber() != null;

        boolean countryProvided =
                request.countryCode() != null;

        if (phoneProvided != countryProvided) {

            throw new IllegalArgumentException(
                    "Country code and phone number must be provided together"
            );
        }

        if (phoneProvided) {

            String countryCode =
                    normalizeCountryCode(
                            request.countryCode()
                    );

            String normalizedPhone =
                    phoneNumberService.normalize(
                            request.phoneNumber().trim(),
                            countryCode
                    );

            if (!normalizedPhone.equals(
                    customer.getPhoneNumber())) {

                if (customerRepository
                        .existsByPhoneNumber(normalizedPhone)) {

                    throw new DuplicateCustomerException(
                            "Phone number is already registered"
                    );
                }

                customer.changePhoneNumber(
                        normalizedPhone
                );
            }
        }

        Customer savedCustomer =
                customerRepository.save(customer);

        return toResponse(savedCustomer);
    }


    @Transactional
    public CustomerResponse suspendCustomer(
            UUID customerId
    ) {

        Customer customer =
                findCustomer(customerId);

        try {

            customer.suspend();

        } catch (IllegalStateException exception) {

            throw new InvalidCustomerStateException(
                    exception.getMessage()
            );
        }

        Customer savedCustomer =
                customerRepository.save(customer);

        eventPublisher.publishEvent(
                new CustomerAccessRevokedEvent(customerId)
        );

        return toResponse(savedCustomer);
    }

    @Transactional
    public CustomerResponse reactivateCustomer(
            UUID customerId
    ) {

        Customer customer =
                findCustomer(customerId);

        try {

            customer.reactivate();

        } catch (IllegalStateException exception) {

            throw new InvalidCustomerStateException(
                    exception.getMessage()
            );
        }

        return toResponse(
                customerRepository.save(customer)
        );
    }


    @Transactional
    public void closeCustomer(UUID customerId) {

        Customer customer =
                findCustomer(customerId);

        try {

            customer.close();

        } catch (IllegalStateException exception) {

            throw new InvalidCustomerStateException(
                    exception.getMessage()
            );
        }

        customerRepository.save(customer);

        eventPublisher.publishEvent(
                new CustomerAccessRevokedEvent(customerId)
        );
    }


    private Customer findCustomer(UUID customerId) {

        return customerRepository
                .findById(customerId)
                .orElseThrow(
                        () -> new CustomerNotFoundException(
                                customerId
                        )
                );
    }

    private String normalizeEmail(String email) {

        return email
                .trim()
                .toLowerCase();
    }

    private String normalizeCountryCode(
            String countryCode
    ) {

        return countryCode
                .trim()
                .toUpperCase();
    }

    public CustomerResponse toResponse(
            Customer customer
    ) {

        return new CustomerResponse(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhoneNumber(),
                customer.getStatus(),
                customer.isEmailVerified(),
                customer.isPhoneVerified(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }
}