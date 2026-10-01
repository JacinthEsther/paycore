package com.fintechplatform.paycore.identity.google;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.customer.event.CustomerAccessRevokedEvent;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.customer.service.CustomerService;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import com.fintechplatform.paycore.identity.exception.IdentityDisabledException;
import com.fintechplatform.paycore.identity.exception.InvalidCredentialsException;
import com.fintechplatform.paycore.identity.exception.InvalidGoogleTokenException;
import com.fintechplatform.paycore.identity.dto.AuthenticationContext;
import com.fintechplatform.paycore.identity.dto.LoginResult;
import com.fintechplatform.paycore.identity.service.AuthenticationService;
import com.fintechplatform.paycore.identity.service.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/**
 * Sign in (and sign up) with Google.
 *
 * The browser gets an ID token from Google; PayCore verifies it, then:
 *
 * <ul>
 *   <li>a Google account seen before signs in to its customer;</li>
 *   <li>otherwise, a customer with the same email is linked to it;</li>
 *   <li>otherwise, a new customer is created, active with a verified
 *       email but no phone number yet.</li>
 * </ul>
 *
 * Linking is where the risk is. If the existing customer never verified
 * their email, whoever set their password never proved they own the
 * address; an attacker could have registered it first, waiting for the
 * real owner to arrive ("pre-account takeover"). Google has now proved
 * ownership, so that password is disabled and every session of the
 * customer is ended, and the real owner continues with Google.
 */
@Service
public class GoogleSignInService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSignInService.class);

    private static final int MAX_NAME_LENGTH = 100;

    private final GoogleIdTokenVerifier verifier;
    private final IdentityService identityService;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final AuthenticationService authenticationService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public GoogleSignInService(
            GoogleIdTokenVerifier verifier,
            IdentityService identityService,
            CustomerRepository customerRepository,
            CustomerService customerService,
            AuthenticationService authenticationService,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate
    ) {
        this.verifier = verifier;
        this.identityService = identityService;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.authenticationService = authenticationService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Not @Transactional: verifying the token may fetch Google's keys, which
     * must not hold a database connection. The writes run in one
     * transaction afterwards.
     */
    public LoginResult signIn(String idToken, AuthenticationContext context) {

        GoogleIdentity google = verifier.verify(idToken);

        if (!google.emailVerified()) {
            throw new InvalidGoogleTokenException(
                    "Your Google account's email address is not verified with Google"
            );
        }

        try {
            return transactionTemplate.execute(status -> signInVerified(google, context));
        } catch (DataIntegrityViolationException exception) {
            // The same Google account signing in twice at once: the other
            // request created the customer or identity first. Use it.
            return transactionTemplate.execute(status -> signInVerified(google, context));
        }
    }

    private LoginResult signInVerified(GoogleIdentity google, AuthenticationContext context) {

        Customer customer =
                identityService
                        .findGoogleIdentity(google.subject())
                        .map(this::customerOf)
                        .orElseGet(() -> linkOrCreate(google));

        return authenticationService.startSessionFor(customer, context);
    }

    private Customer customerOf(Identity identity) {

        if (!identity.isEnabled()) {
            throw new IdentityDisabledException();
        }

        return identity.getCustomer();
    }

    private Customer linkOrCreate(GoogleIdentity google) {

        String email = google.email().trim().toLowerCase(Locale.ROOT);

        return customerRepository
                .findByEmail(email)
                .map(existing -> link(existing, google))
                .orElseGet(() -> create(google, email));
    }

    private Customer link(Customer customer, GoogleIdentity google) {

        if (!customer.getStatus().canAuthenticate()) {
            throw new InvalidCredentialsException();
        }

        if (identityService.findIdentity(customer, IdentityProvider.GOOGLE).isPresent()) {
            throw new InvalidGoogleTokenException(
                    "This PayCore account is linked to a different Google account"
            );
        }

        if (!customer.isEmailVerified()) {

            identityService
                    .findIdentity(customer, IdentityProvider.PASSWORD)
                    .ifPresent(identityService::disable);

            // Ends every session and refresh token, in this transaction.
            eventPublisher.publishEvent(new CustomerAccessRevokedEvent(customer.getId()));

            customer.verifyEmail();

            if (customer.getStatus() == CustomerStatus.PENDING_VERIFICATION) {
                customer.activate();
            }

            log.warn(
                    "Customer {} had an unverified email and is now linked to Google: "
                            + "its password was disabled and its sessions ended",
                    customer.getId()
            );
        }

        identityService.createGoogleIdentity(customer, google.subject());

        return customerRepository.save(customer);
    }

    private Customer create(GoogleIdentity google, String email) {

        String firstName = name(google.givenName(), email.substring(0, email.indexOf('@')));
        String lastName = name(google.familyName(), "");

        Customer customer = customerService.createVerifiedCustomer(firstName, lastName, email);

        identityService.createGoogleIdentity(customer, google.subject());

        log.info("Customer {} signed up with Google", customer.getId());

        return customer;
    }

    private static String name(String value, String fallback) {

        String name = value == null || value.isBlank() ? fallback : value.trim();

        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }
}
