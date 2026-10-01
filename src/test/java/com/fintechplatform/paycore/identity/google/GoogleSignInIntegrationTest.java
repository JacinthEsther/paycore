package com.fintechplatform.paycore.identity.google;

import com.fintechplatform.paycore.identity.exception.InvalidGoogleTokenException;
import com.fintechplatform.paycore.notification.EmailSentEvent;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign in and sign up with Google, with Google's token check stubbed
 * (JwtGoogleIdTokenVerifierTest covers the real one): new customers,
 * returning customers, linking by email, and the pre-account takeover
 * defence.
 */
@Testcontainers
@SpringBootTest(properties = "paycore.google.client-id=paycore-test.apps.googleusercontent.com")
@AutoConfigureMockMvc
@RecordApplicationEvents
class GoogleSignInIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final GoogleIdentity ADA =
            new GoogleIdentity("google-sub-ada", "Ada.Obi@Gmail.com", true, "Ada", "Obi");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private GoogleIdTokenVerifier verifier;

    @BeforeEach
    void cleanDatabase() {
        for (String table : new String[]{
                "email_verification_tokens",
                "kyc_verifications",
                "kyc_documents",
                "kyc_profiles",
                "refresh_tokens",
                "login_sessions",
                "identities",
                "role_assignment_events",
                "customer_roles",
                "customers"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }

        when(verifier.verify(anyString())).thenThrow(new InvalidGoogleTokenException("The Google sign-in could not be verified"));
        when(verifier.verify(eq("ada-token"))).thenReturn(ADA);
    }

    @Test
    void shouldPublishTheClientIdForTheUi() throws Exception {

        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(jsonPath("$.google.enabled").value(true))
                .andExpect(jsonPath("$.google.clientId").value("paycore-test.apps.googleusercontent.com"));
    }

    @Test
    void aNewGoogleUserShouldBeCreatedActiveWithoutAPhoneNumber() throws Exception {

        String body =
                google("ada-token")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.email").value("ada.obi@gmail.com"))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                        .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.accessToken");

        mockMvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Obi"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist());

        // A normal customer: CUSTOMER role, a KYC profile, no password.
        mockMvc.perform(get("/api/v1/kyc/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT provider FROM identities", String.class
        )).containsExactly("GOOGLE");

        // Google already verified the address: no link is sent.
        assertThat(events.stream(EmailSentEvent.class)).isEmpty();
    }

    @Test
    void theSameGoogleAccountShouldSignInToTheSameCustomer() throws Exception {

        String first = JsonPath.read(google("ada-token").andReturn().getResponse().getContentAsString(), "$.customerId");
        String second = JsonPath.read(google("ada-token").andReturn().getResponse().getContentAsString(), "$.customerId");

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customers", Integer.class)).isEqualTo(1);
    }

    @Test
    void aVerifiedPasswordCustomerShouldBeLinkedAndKeepTheirPassword() throws Exception {

        String customerId = register("ada.obi@gmail.com");
        jdbcTemplate.update(
                "UPDATE customers SET email_verified = true, status = 'ACTIVE' WHERE id = ?::uuid", customerId
        );

        google("ada-token")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId));

        // Both ways in now work.
        login("ada.obi@gmail.com").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForList(
                "SELECT provider FROM identities WHERE customer_id = ?::uuid ORDER BY provider", String.class, customerId
        )).containsExactly("GOOGLE", "PASSWORD");
    }

    @Test
    void anUnverifiedAccountWithTheSameEmailShouldNotKeepItsPassword() throws Exception {

        // Someone registered Ada's address with a password but never
        // proved they own it...
        String customerId = register("ada.obi@gmail.com");
        String squatterToken = JsonPath.read(
                login("ada.obi@gmail.com").andReturn().getResponse().getContentAsString(), "$.accessToken"
        );

        // ...then the real Ada signs in with Google.
        google("ada-token")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // The password no longer works, and the earlier session is over.
        login("ada.obi@gmail.com")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("IDENTITY_DISABLED"));

        mockMvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + squatterToken))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_verified FROM customers WHERE id = ?::uuid", Boolean.class, customerId
        )).isTrue();
    }

    @Test
    void shouldRejectBadTokensAndUnverifiedGoogleEmails() throws Exception {

        google("forged")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_GOOGLE_TOKEN"));

        when(verifier.verify(eq("unverified-token"))).thenReturn(
                new GoogleIdentity("google-sub-x", "x@example.com", false, "X", "Y")
        );

        google("unverified-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_GOOGLE_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customers", Integer.class)).isZero();
    }

    @Test
    void aSuspendedCustomerShouldNotSignInWithGoogle() throws Exception {

        google("ada-token").andExpect(status().isOk());
        jdbcTemplate.update("UPDATE customers SET status = 'SUSPENDED'");

        google("ada-token").andExpect(status().isUnauthorized());
    }

    @Test
    void aGoogleUserShouldAddAPhoneNumberBeforeSubmittingKyc() throws Exception {

        String token = JsonPath.read(google("ada-token").andReturn().getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(post("/api/v1/kyc/start").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        submitKyc(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing", hasItem("phone number on your profile")));

        mockMvc.perform(patch("/api/v1/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\": \"NG\", \"phoneNumber\": \"08012345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+2348012345678"));

        submitKyc(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing", not(hasItem("phone number on your profile"))));
    }

    // ------------------------------------------------------------------

    private ResultActions google(String credential) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\": \"%s\"}".formatted(credential)));
    }

    private ResultActions submitKyc(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/kyc/submit").header("Authorization", "Bearer " + token));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"Password123\"}".formatted(email)));
    }

    private String register(String email) throws Exception {
        String body =
                mockMvc.perform(post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "firstName": "Someone",
                                            "lastName": "Else",
                                            "email": "%s",
                                            "countryCode": "NG",
                                            "phoneNumber": "08099990000",
                                            "password": "Password123"
                                        }
                                        """.formatted(email)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
