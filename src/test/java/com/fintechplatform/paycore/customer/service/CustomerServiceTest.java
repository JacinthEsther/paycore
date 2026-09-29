package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.exception.RoleNotFoundException;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import com.fintechplatform.paycore.customer.dto.request.UpdateCustomerRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.event.CustomerAccessRevokedEvent;
import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.exception.DuplicateCustomerException;
import com.fintechplatform.paycore.customer.exception.InvalidCustomerStateException;
import com.fintechplatform.paycore.customer.exception.InvalidPhoneNumberException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.service.IdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PhoneNumberService phoneNumberService;

    @Mock
    private IdentityService identityService;

    @Mock
    private RoleAssignmentService roleAssignmentService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CustomerService customerService;

    private RegisterCustomerRequest request;

    @BeforeEach
    void setUp() {
        request = new RegisterCustomerRequest(
                "Esther",
                "Agboniro",
                "ESTHER@EXAMPLE.COM",
                "NG",
                "08012345678",
                "Password123!"
        );
    }

    // ============================================================
    // REGISTRATION
    // ============================================================

    @Test
    void shouldAssignCustomerRoleOnRegistration() {

        when(phoneNumberService.normalize("08012345678", "NG"))
                .thenReturn("+2348012345678");

        when(passwordEncoder.encode("Password123!"))
                .thenReturn("hashed-password");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.register(request);

        ArgumentCaptor<Customer> captor =
                ArgumentCaptor.forClass(Customer.class);

        verify(customerRepository)
                .save(captor.capture());

        // System-assigned, so no acting admin is recorded.
        verify(roleAssignmentService).assignRole(
                eq(captor.getValue()),
                eq(RoleName.CUSTOMER),
                isNull(),
                anyString()
        );
    }

    @Test
    void shouldFailRegistrationWhenDefaultRoleIsMissing() {

        when(phoneNumberService.normalize("08012345678", "NG"))
                .thenReturn("+2348012345678");

        when(passwordEncoder.encode("Password123!"))
                .thenReturn("hashed-password");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        doThrow(new RoleNotFoundException(RoleName.CUSTOMER))
                .when(roleAssignmentService)
                .assignRole(
                        any(Customer.class),
                        eq(RoleName.CUSTOMER),
                        isNull(),
                        anyString()
                );

        // The exception rolls back the @Transactional registration, so the
        // saved customer is not committed.
        assertThatThrownBy(() ->
                customerService.register(request)
        )
                .isInstanceOf(RoleNotFoundException.class)
                .hasMessageContaining(RoleName.CUSTOMER);

        verify(identityService, never())
                .createPasswordIdentity(any(Customer.class), anyString());
    }

    @Test
    void shouldRegisterCustomerSuccessfully() {

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(
                "esther@example.com"
        )).thenReturn(false);

        when(customerRepository.existsByPhoneNumber(
                "+2348012345678"
        )).thenReturn(false);

        when(passwordEncoder.encode(
                "Password123!"
        )).thenReturn("hashed-password");
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerResponse response =
                customerService.register(request);

        assertThat(response).isNotNull();

        assertThat(response.firstName())
                .isEqualTo("Esther");

        assertThat(response.lastName())
                .isEqualTo("Agboniro");

        assertThat(response.email())
                .isEqualTo("esther@example.com");

        assertThat(response.phoneNumber())
                .isEqualTo("+2348012345678");

        assertThat(response.status())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION);

        assertThat(response.emailVerified())
                .isFalse();

        assertThat(response.phoneVerified())
                .isFalse();

        verify(passwordEncoder)
                .encode("Password123!");

        verify(customerRepository)
                .save(any(Customer.class));

        verify(identityService)
                .createPasswordIdentity(
                        any(Customer.class),
                        eq("hashed-password")
                );

        // KYC listens for this to create the customer's profile.
        verify(eventPublisher)
                .publishEvent(any(CustomerRegisteredEvent.class));
    }

    @Test
    void shouldCreateCustomerWithCorrectValues() {

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(
                "esther@example.com"
        )).thenReturn(false);

        when(customerRepository.existsByPhoneNumber(
                "+2348012345678"
        )).thenReturn(false);

        when(passwordEncoder.encode(
                "Password123!"
        )).thenReturn("hashed-password");
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.register(request);

        ArgumentCaptor<Customer> captor =
                ArgumentCaptor.forClass(Customer.class);

        verify(customerRepository)
                .save(captor.capture());

        Customer savedCustomer = captor.getValue();

        assertThat(savedCustomer.getFirstName())
                .isEqualTo("Esther");

        assertThat(savedCustomer.getLastName())
                .isEqualTo("Agboniro");

        assertThat(savedCustomer.getEmail())
                .isEqualTo("esther@example.com");

        assertThat(savedCustomer.getPhoneNumber())
                .isEqualTo("+2348012345678");

        assertThat(savedCustomer.getStatus())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION);

        assertThat(savedCustomer.isEmailVerified())
                .isFalse();

        assertThat(savedCustomer.isPhoneVerified())
                .isFalse();

        assertThat(savedCustomer.getCreatedAt())
                .isNotNull();

        assertThat(savedCustomer.getUpdatedAt())
                .isNotNull();

        /*
         * Password is no longer part of Customer.
         * It must be passed to IdentityService instead.
         */
        verify(identityService)
                .createPasswordIdentity(
                        same(savedCustomer),
                        eq("hashed-password")
                );
    }

    @Test
    void shouldHashPasswordBeforeCreatingIdentity() {

        when(phoneNumberService.normalize(
                anyString(),
                anyString()
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(anyString()))
                .thenReturn(false);

        when(customerRepository.existsByPhoneNumber(anyString()))
                .thenReturn(false);

        when(passwordEncoder.encode("Password123!"))
                .thenReturn("bcrypt-hash");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.register(request);

        verify(passwordEncoder)
                .encode("Password123!");

        /*
         * The encoded password must be sent to IdentityService.
         */
        verify(identityService)
                .createPasswordIdentity(
                        any(Customer.class),
                        eq("bcrypt-hash")
                );

        /*
         * The raw password must never be sent to IdentityService.
         */
        verify(identityService, never())
                .createPasswordIdentity(
                        any(Customer.class),
                        eq("Password123!")
                );
    }

    @Test
    void shouldRejectDuplicateEmail() {

        when(customerRepository.existsByEmail(
                "esther@example.com"
        )).thenReturn(true);

        assertThatThrownBy(() ->
                customerService.register(request)
        )
                .isInstanceOf(DuplicateCustomerException.class)
                .hasMessage("Email is already registered");

        verify(customerRepository)
                .existsByEmail("esther@example.com");

        verify(customerRepository, never())
                .save(any(Customer.class));

        verify(passwordEncoder, never())
                .encode(anyString());

        verify(identityService, never())
                .createPasswordIdentity(
                        any(Customer.class),
                        anyString()
                );
    }

    @Test
    void shouldRejectDuplicatePhoneNumber() {

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(
                "esther@example.com"
        )).thenReturn(false);

        when(customerRepository.existsByPhoneNumber(
                "+2348012345678"
        )).thenReturn(true);

        assertThatThrownBy(() ->
                customerService.register(request)
        )
                .isInstanceOf(DuplicateCustomerException.class)
                .hasMessage("Phone number is already registered");

        verify(customerRepository, never())
                .save(any(Customer.class));

        verify(passwordEncoder, never())
                .encode(anyString());

        verify(identityService, never())
                .createPasswordIdentity(
                        any(Customer.class),
                        anyString()
                );
    }

    @Test
    void shouldNormalizeEmailBeforeCheckingDuplicate() {

        RegisterCustomerRequest requestWithMessyEmail =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "  ESTHER@EXAMPLE.COM  ",
                        "NG",
                        "08012345678",
                        "Password123!"
                );

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(
                "esther@example.com"
        )).thenReturn(false);

        when(customerRepository.existsByPhoneNumber(
                "+2348012345678"
        )).thenReturn(false);

        when(passwordEncoder.encode(anyString()))
                .thenReturn("hash");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.register(requestWithMessyEmail);

        verify(customerRepository)
                .existsByEmail("esther@example.com");
    }

    @Test
    void shouldNormalizeCountryCodeBeforePhoneParsing() {

        RegisterCustomerRequest requestWithLowercaseCountry =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        " ng ",
                        "08012345678",
                        "Password123!"
                );

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(anyString()))
                .thenReturn(false);

        when(customerRepository.existsByPhoneNumber(anyString()))
                .thenReturn(false);

        when(passwordEncoder.encode(anyString()))
                .thenReturn("hash");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.register(requestWithLowercaseCountry);

        verify(phoneNumberService)
                .normalize(
                        "08012345678",
                        "NG"
                );
    }

    @Test
    void shouldRejectInvalidPhoneNumber() {

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenThrow(
                new InvalidPhoneNumberException(
                        "Invalid phone number"
                )
        );

        assertThatThrownBy(() ->
                customerService.register(request)
        )
                .isInstanceOf(InvalidPhoneNumberException.class)
                .hasMessage("Invalid phone number");

        verify(customerRepository, never())
                .existsByEmail(anyString());

        verify(customerRepository, never())
                .existsByPhoneNumber(anyString());

        verify(customerRepository, never())
                .save(any(Customer.class));

        verify(passwordEncoder, never())
                .encode(anyString());

        verify(identityService, never())
                .createPasswordIdentity(
                        any(Customer.class),
                        anyString()
                );
    }

    @Test
    void shouldRejectInvalidPhoneDuringRegistration() {

        RegisterCustomerRequest invalidRequest =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "12345",
                        "Password123"
                );

        when(phoneNumberService.normalize(
                "12345",
                "NG"
        )).thenThrow(
                new InvalidPhoneNumberException(
                        "Invalid phone number"
                )
        );

        assertThatThrownBy(() ->
                customerService.register(invalidRequest)
        )
                .isInstanceOf(InvalidPhoneNumberException.class)
                .hasMessage("Invalid phone number");

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldNormalizeEmailDuringRegistration() {

        RegisterCustomerRequest requestWithSpaces =
                new RegisterCustomerRequest(
                        " Esther ",
                        " Agboniro ",
                        " ESTHER@EXAMPLE.COM ",
                        " ng ",
                        "08012345678",
                        "Password123"
                );

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        when(customerRepository.existsByEmail(
                "esther@example.com"
        )).thenReturn(false);

        when(customerRepository.existsByPhoneNumber(
                "+2348012345678"
        )).thenReturn(false);

        when(passwordEncoder.encode("Password123"))
                .thenReturn("hashed-password");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerResponse result =
                customerService.register(requestWithSpaces);

        assertThat(result.email())
                .isEqualTo("esther@example.com");

        assertThat(result.firstName())
                .isEqualTo("Esther");

        assertThat(result.lastName())
                .isEqualTo("Agboniro");

        verify(identityService)
                .createPasswordIdentity(
                        any(Customer.class),
                        eq("hashed-password")
                );
    }

    // ============================================================
    // GET CUSTOMER
    // ============================================================

    @Test
    void shouldGetCustomerProfile() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        CustomerResponse result =
                customerService.getCustomer(customerId);

        assertThat(result.firstName())
                .isEqualTo("Esther");

        assertThat(result.lastName())
                .isEqualTo("Agboniro");

        assertThat(result.email())
                .isEqualTo("esther@example.com");

        assertThat(result.phoneNumber())
                .isEqualTo("+2348012345678");

        assertThat(result.status())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION);

        verify(customerRepository)
                .findById(customerId);
    }

    @Test
    void shouldThrowWhenCustomerDoesNotExist() {

        UUID customerId = UUID.randomUUID();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                customerService.getCustomer(customerId)
        )
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining(
                        customerId.toString()
                );
    }

    // ============================================================
    // PROFILE UPDATE
    // ============================================================

    @Test
    void shouldUpdateCustomerFirstName() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        "Jane",
                        null,
                        null,
                        null,
                        null
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.firstName())
                .isEqualTo("Jane");

        assertThat(result.lastName())
                .isEqualTo("Agboniro");

        verify(customerRepository)
                .save(customer);
    }

    @Test
    void shouldUpdateCustomerLastName() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        "Smith",
                        null,
                        null,
                        null
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.lastName())
                .isEqualTo("Smith");

        assertThat(result.firstName())
                .isEqualTo("Esther");

        verify(customerRepository)
                .save(customer);
    }

    @Test
    void shouldUpdateFirstAndLastNameTogether() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        "Jane",
                        "Smith",
                        null,
                        null,
                        null
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.firstName())
                .isEqualTo("Jane");

        assertThat(result.lastName())
                .isEqualTo("Smith");

        verify(customerRepository)
                .save(customer);
    }

    @Test
    void shouldUpdateEmailAndResetVerification() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "old@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();

        assertThat(customer.isEmailVerified())
                .isTrue();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.existsByEmail(
                "new@example.com"
        )).thenReturn(false);

        when(customerRepository.save(customer))
                .thenReturn(customer);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        "new@example.com",
                        null,
                        null
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.email())
                .isEqualTo("new@example.com");

        assertThat(result.emailVerified())
                .isFalse();
    }

    @Test
    void shouldRejectDuplicateEmailDuringUpdate() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "old@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.existsByEmail(
                "existing@example.com"
        )).thenReturn(true);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        "existing@example.com",
                        null,
                        null
                );

        assertThatThrownBy(() ->
                customerService.updateProfile(
                        customerId,
                        request
                )
        )
                .isInstanceOf(DuplicateCustomerException.class)
                .hasMessage("Email is already registered");

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldNotChangeEmailWhenSameEmailIsProvided() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        "ESTHER@EXAMPLE.COM",
                        null,
                        null
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.email())
                .isEqualTo("esther@example.com");

        assertThat(result.emailVerified())
                .isTrue();

        verify(customerRepository, never())
                .existsByEmail(anyString());
    }

    @Test
    void shouldUpdatePhoneNumber() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyPhone();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(phoneNumberService.normalize(
                "08098765432",
                "NG"
        )).thenReturn("+2348098765432");

        when(customerRepository.existsByPhoneNumber(
                "+2348098765432"
        )).thenReturn(false);

        when(customerRepository.save(customer))
                .thenReturn(customer);

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        null,
                        "NG",
                        "08098765432"
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.phoneNumber())
                .isEqualTo("+2348098765432");

        assertThat(result.phoneVerified())
                .isFalse();

        verify(phoneNumberService)
                .normalize(
                        "08098765432",
                        "NG"
                );
    }

    @Test
    void shouldNotChangePhoneWhenSamePhoneIsProvided() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyPhone();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        when(phoneNumberService.normalize(
                "08012345678",
                "NG"
        )).thenReturn("+2348012345678");

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        null,
                        "NG",
                        "08012345678"
                );

        CustomerResponse result =
                customerService.updateProfile(
                        customerId,
                        request
                );

        assertThat(result.phoneNumber())
                .isEqualTo("+2348012345678");

        assertThat(result.phoneVerified())
                .isTrue();

        verify(customerRepository, never())
                .existsByPhoneNumber(anyString());
    }

    @Test
    void shouldRejectCountryCodeWithoutPhoneNumber() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        null,
                        "NG",
                        null
                );

        assertThatThrownBy(() ->
                customerService.updateProfile(
                        customerId,
                        request
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Country code and phone number must be provided together"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldRejectInvalidPhoneNumberDuringUpdate() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(phoneNumberService.normalize(
                "12345",
                "NG"
        )).thenThrow(
                new InvalidPhoneNumberException(
                        "Invalid phone number"
                )
        );

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        null,
                        "NG",
                        "12345"
                );

        assertThatThrownBy(() ->
                customerService.updateProfile(
                        customerId,
                        request
                )
        )
                .isInstanceOf(InvalidPhoneNumberException.class)
                .hasMessage("Invalid phone number");

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldNotChangeEmailForClosedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "old@example.com",
                "+2348012345678"
        );

        customer.close();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        null,
                        null,
                        "new@example.com",
                        null,
                        null
                );

        assertThatThrownBy(() ->
                customerService.updateProfile(
                        customerId,
                        request
                )
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Closed customers cannot update their profile"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldThrowWhenUpdatingNonExistingCustomer() {

        UUID customerId = UUID.randomUUID();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.empty());

        UpdateCustomerRequest request =
                new UpdateCustomerRequest(
                        "Jane",
                        null,
                        null,
                        null,
                        null
                );

        assertThatThrownBy(() ->
                customerService.updateProfile(
                        customerId,
                        request
                )
        )
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining(
                        customerId.toString()
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    // ============================================================
    // SUSPENSION
    // ============================================================

    @Test
    void shouldSuspendActiveCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();
        customer.activate();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        CustomerResponse result =
                customerService.suspendCustomer(customerId);

        assertThat(result.status())
                .isEqualTo(CustomerStatus.SUSPENDED);

        verify(customerRepository)
                .save(customer);

        verify(eventPublisher)
                .publishEvent(new CustomerAccessRevokedEvent(customerId));
    }

    @Test
    void shouldNotSuspendPendingCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.suspendCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Only active customers can be suspended"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));

        verify(eventPublisher, never())
                .publishEvent(any(CustomerAccessRevokedEvent.class));
    }

    @Test
    void shouldNotSuspendAlreadySuspendedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();
        customer.activate();
        customer.suspend();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.suspendCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Only active customers can be suspended"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldNotSuspendClosedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.close();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.suspendCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Only active customers can be suspended"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldThrowWhenSuspendingNonExistingCustomer() {

        UUID customerId = UUID.randomUUID();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                customerService.suspendCustomer(customerId)
        )
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining(
                        customerId.toString()
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    // ============================================================
    // REACTIVATION
    // ============================================================

    @Test
    void shouldReactivateSuspendedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();
        customer.activate();
        customer.suspend();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        CustomerResponse result =
                customerService.reactivateCustomer(customerId);

        assertThat(result.status())
                .isEqualTo(CustomerStatus.ACTIVE);

        verify(customerRepository)
                .save(customer);
    }

    @Test
    void shouldNotReactivateActiveCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();
        customer.activate();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.reactivateCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Only suspended customers can be reactivated"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldNotReactivatePendingCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.reactivateCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Only suspended customers can be reactivated"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldNotReactivateClosedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.close();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.reactivateCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Only suspended customers can be reactivated"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    // ============================================================
    // CLOSURE
    // ============================================================

    @Test
    void shouldCloseCustomerAccount() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();
        customer.activate();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        customerService.closeCustomer(customerId);

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.CLOSED);

        verify(customerRepository)
                .save(customer);

        verify(eventPublisher)
                .publishEvent(new CustomerAccessRevokedEvent(customerId));
    }

    @Test
    void shouldCloseSuspendedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.verifyEmail();
        customer.activate();
        customer.suspend();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        customerService.closeCustomer(customerId);

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.CLOSED);

        verify(customerRepository)
                .save(customer);
    }

    @Test
    void shouldClosePendingCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(customerRepository.save(customer))
                .thenReturn(customer);

        customerService.closeCustomer(customerId);

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.CLOSED);

        verify(customerRepository)
                .save(customer);
    }

    @Test
    void shouldNotCloseAlreadyClosedCustomer() {

        UUID customerId = UUID.randomUUID();

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        customer.close();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() ->
                customerService.closeCustomer(customerId)
        )
                .isInstanceOf(InvalidCustomerStateException.class)
                .hasMessage(
                        "Customer account is already closed"
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldThrowWhenClosingNonExistingCustomer() {

        UUID customerId = UUID.randomUUID();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                customerService.closeCustomer(customerId)
        )
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining(
                        customerId.toString()
                );

        verify(customerRepository, never())
                .save(any(Customer.class));
    }
}