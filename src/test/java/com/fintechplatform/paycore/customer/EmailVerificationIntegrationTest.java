package com.fintechplatform.paycore.customer;

import com.fintechplatform.paycore.notification.EmailSentEvent;
import com.fintechplatform.paycore.notification.OutgoingEmail;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email verification end to end: the link sent at registration, activation,
 * one-time and expiring links, resend limits, and email changes.
 */
@Testcontainers
@SpringBootTest(properties = "paycore.email-verification.public-url=https://app.paycore.test/")
@AutoConfigureMockMvc
@RecordApplicationEvents
class EmailVerificationIntegrationTest {

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

    private static final Pattern LINK = Pattern.compile("https://app\\.paycore\\.test/verify-email\\?token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

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
    }

    @Test
    void registrationShouldSendALinkThatVerifiesAndActivates() throws Exception {

        register("ada@example.com", "08012340001");

        OutgoingEmail email = onlyEmailTo("ada@example.com");
        assertThat(email.subject()).isEqualTo("Verify your PayCore email address");
        assertThat(email.body()).contains("Hi Ada").contains("expires in 24 hours");

        // Signing in works before verification; the customer is pending.
        String token = login("ada@example.com");
        me(token)
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        verify(tokenIn(email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        me(token).andExpect(jsonPath("$.status").value("ACTIVE"));

        // Only the hash is stored, never the token itself.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM email_verification_tokens", String.class
        )).hasSize(64).isNotEqualTo(tokenIn(email));
    }

    @Test
    void aLinkShouldWorkOnlyOnce() throws Exception {

        register("ada@example.com", "08012340001");
        String link = tokenIn(onlyEmailTo("ada@example.com"));

        verify(link).andExpect(status().isOk());

        verify(link)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_VERIFICATION_TOKEN"));
    }

    @Test
    void expiredAndUnknownLinksShouldBeRejected() throws Exception {

        register("ada@example.com", "08012340001");
        String link = tokenIn(onlyEmailTo("ada@example.com"));

        jdbcTemplate.update(
                "UPDATE email_verification_tokens "
                        + "SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'"
        );

        verify(link).andExpect(status().isBadRequest());
        verify("not-a-real-token").andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \" \"}"))
                .andExpect(status().isBadRequest());

        me(login("ada@example.com")).andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void resendShouldBeLimitedAndReplaceTheOldLink() throws Exception {

        register("ada@example.com", "08012340001");
        String first = tokenIn(onlyEmailTo("ada@example.com"));
        String token = login("ada@example.com");

        // The registration link was just sent: wait a minute.
        resend(token)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("VERIFICATION_EMAIL_RATE_LIMIT"))
                .andExpect(header().exists("Retry-After"));

        ageTokens();
        resend(token).andExpect(status().isAccepted());

        List<OutgoingEmail> sent = emailsTo("ada@example.com");
        assertThat(sent).hasSize(2);
        String second = tokenIn(sent.get(1));

        // Only the newest link works.
        verify(first).andExpect(status().isBadRequest());
        verify(second).andExpect(status().isOk());

        resend(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_VERIFIED"));
    }

    @Test
    void resendShouldStopAtFiveADay() throws Exception {

        register("ada@example.com", "08012340001");
        String token = login("ada@example.com");

        for (int i = 0; i < 4; i++) {
            ageTokens();
            resend(token).andExpect(status().isAccepted());
        }

        ageTokens();
        resend(token).andExpect(status().isTooManyRequests());

        assertThat(emailsTo("ada@example.com")).hasSize(5);
    }

    @Test
    void changingEmailShouldRequireVerifyingTheNewAddress() throws Exception {

        register("ada@example.com", "08012340001");
        verify(tokenIn(onlyEmailTo("ada@example.com"))).andExpect(status().isOk());
        String token = login("ada@example.com");

        mockMvc.perform(patch("/api/v1/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"Ada.New@Example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada.new@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(tokenIn(onlyEmailTo("ada.new@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        // The password login follows the new address...
        login("ada.new@example.com");

        // ...and the old one is free for someone else to register.
        register("ada@example.com", "08012340002");
    }

    @Test
    void aLinkSentBeforeAnEmailChangeShouldNotVerifyTheNewAddress() throws Exception {

        register("ada@example.com", "08012340001");
        String oldLink = tokenIn(onlyEmailTo("ada@example.com"));
        String token = login("ada@example.com");

        mockMvc.perform(patch("/api/v1/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"ada.new@example.com\"}"))
                .andExpect(status().isOk());

        // Even if it had not been replaced by the new link.
        jdbcTemplate.update("UPDATE email_verification_tokens SET used_at = NULL");

        verify(oldLink).andExpect(status().isBadRequest());
        me(token).andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void googleSignInShouldReportItselfOffWithoutAClientId() throws Exception {

        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value(true))
                .andExpect(jsonPath("$.google.enabled").value(false))
                .andExpect(jsonPath("$.google.clientId").doesNotExist());

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\": \"anything\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("GOOGLE_SIGN_IN_DISABLED"));
    }

    // ------------------------------------------------------------------

    /** Moves every link back past the resend cooldown. */
    private void ageTokens() {
        jdbcTemplate.update(
                "UPDATE email_verification_tokens "
                        + "SET created_at = created_at - interval '2 minutes', "
                        + "    expires_at = expires_at - interval '2 minutes'"
        );
    }

    private List<OutgoingEmail> emailsTo(String address) {
        return events.stream(EmailSentEvent.class)
                .map(EmailSentEvent::email)
                .filter(email -> email.to().equals(address))
                .toList();
    }

    private OutgoingEmail onlyEmailTo(String address) {
        List<OutgoingEmail> emails = emailsTo(address);
        assertThat(emails).hasSize(1);
        return emails.getFirst();
    }

    private static String tokenIn(OutgoingEmail email) {
        Matcher matcher = LINK.matcher(email.body());
        assertThat(matcher.find()).as("verification link in: %s", email.body()).isTrue();
        return matcher.group(1);
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\"}".formatted(token)));
    }

    private ResultActions resend(String accessToken) throws Exception {
        return mockMvc.perform(post("/api/v1/customers/me/verification-email")
                .header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + accessToken));
    }

    private void register(String email, String phone) throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "firstName": "Ada",
                                    "lastName": "Obi",
                                    "email": "%s",
                                    "countryCode": "NG",
                                    "phoneNumber": "%s",
                                    "password": "Password123"
                                }
                                """.formatted(email, phone)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String body =
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\": \"%s\", \"password\": \"Password123\"}".formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
