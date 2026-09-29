package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import com.fintechplatform.paycore.customer.dto.request.UpdateCustomerRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerPageResponse;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.dto.response.CustomerSummaryResponse;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.exception.DuplicateCustomerException;
import com.fintechplatform.paycore.customer.exception.InvalidCustomerStateException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.service.IdentityService;
import com.fintechplatform.paycore.kyc.dto.PageInfo;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNumberService phoneNumberService;
    private final IdentityService identityService;
    private final RoleAssignmentService roleAssignmentService;
    private final ApplicationEventPublisher eventPublisher;


    public CustomerService(
            CustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            PhoneNumberService phoneNumberService,
            IdentityService identityService,
            RoleAssignmentService roleAssignmentService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneNumberService = phoneNumberService;
        this.identityService = identityService;
        this.roleAssignmentService = roleAssignmentService;
        this.eventPublisher = eventPublisher;
    }


    @Transactional
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

        if (customerRepository.existsByEmail(email)) {
            throw new DuplicateCustomerException(
                    "Email is already registered"
            );
        }

        if (customerRepository.existsByPhoneNumber(phoneNumber)) {
            throw new DuplicateCustomerException(
                    "Phone number is already registered"
            );
        }

        String passwordHash =
                passwordEncoder.encode(request.password());

        Customer customer = Customer.create(
                request.firstName().trim(),
                request.lastName().trim(),
                email,
                phoneNumber
        );

        Customer savedCustomer =
                customerRepository.save(customer);

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

        return toResponse(
                customerRepository.save(customer)
        );
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

    private CustomerResponse toResponse(
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