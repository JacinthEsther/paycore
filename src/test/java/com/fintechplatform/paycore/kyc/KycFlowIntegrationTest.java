package com.fintechplatform.paycore.kyc;

import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.repository.RoleRepository;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.KycProviderException;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.kyc.provider.KycProviderResult;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REGISTER -> LOGIN -> CREATE KYC -> START -> UPLOAD DOCUMENT -> BVN CHECK
 * -> SUBMIT -> START REVIEW -> VERIFIED / REJECTED / ADDITIONAL_INFO, over
 * real HTTP, JWT, PostgreSQL and file storage. Only the KYC provider is
 * mocked: builds never call Dojah.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KycFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    /**
     * A tiny but valid-looking PDF header; the upload only sniffs magic bytes.
     */
    private static final byte[] PDF =
            "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);

    private static final Path DOCUMENT_DIR = createDocumentDir();

    private static Path createDocumentDir() {
        try {
            return Files.createTempDirectory("paycore-kyc-documents");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "paycore.kyc.documents.storage-dir",
                DOCUMENT_DIR::toString
        );

        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private KycProvider kycProvider;

    @BeforeEach
    void cleanDatabase() {

        for (String table : List.of(
                "kyc_verifications",
                "kyc_documents",
                "kyc_profiles",
                "refresh_tokens",
                "login_sessions",
                "identities",
                "role_assignment_events",
                "customer_roles",
                "customers"
        )) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }

    @Test
    void shouldTakeCustomerFromRegistrationToVerifiedKyc() throws Exception {

        String customer = registerAndLogin("kyc@example.com", "08011111111");

        String kycId =
                JsonPath.read(
                        authorized(get("/api/v1/kyc"), customer)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        // Nothing can be added before the customer starts.
        uploadDocument(customer, "NATIONAL_ID")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_KYC_STATE"));

        authorized(post("/api/v1/kyc/start"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        uploadDocument(customer, "NATIONAL_ID")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kycStatus").value("IN_PROGRESS"));

        // The file is on disk under a generated name; only metadata is in
        // the database.
        String storageKey =
                jdbcTemplate.queryForObject(
                        "SELECT storage_key FROM kyc_documents", String.class
                );

        assertThat(storageKey).startsWith(kycId + "/").endsWith(".pdf");
        assertThat(DOCUMENT_DIR.resolve(storageKey)).hasBinaryContent(PDF);

        // First attempt: typo in the name, so the provider says FAILED.
        when(kycProvider.verifyBvn(any()))
                .thenReturn(providerResult(VerificationResult.FAILED,
                        "Details do not match BVN record: last_name"))
                .thenReturn(providerResult(VerificationResult.PASSED, null));

        verifyBvn(customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("FAILED"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // PASSED is one verification signal, not a finished KYC package.
        verifyBvn(customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        authorized(post("/api/v1/kyc/submit"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Submitted packages are frozen.
        uploadDocument(customer, "PASSPORT")
                .andExpect(status().isConflict());

        authorized(get("/api/v1/kyc/status"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Both attempts are kept; the BVN itself is stored nowhere.
        assertThat(jdbcTemplate.queryForList(
                "SELECT result FROM kyc_verifications ORDER BY created_at, id",
                String.class
        )).containsExactly("FAILED", "PASSED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kyc_verifications "
                        + "WHERE provider_reference LIKE '%22222222222%' "
                        + "OR reason LIKE '%22222222222%'",
                Integer.class
        )).isZero();

        // A normal customer cannot approve, not even their own KYC.
        authorized(post("/api/v1/kyc/{id}/approve", kycId), customer)
                .andExpect(status().isForbidden());

        String admin = registerAdmin("admin@example.com", "08011111112");

        // Cannot approve before a reviewer has picked it up.
        authorized(post("/api/v1/kyc/{id}/approve", kycId), admin)
                .andExpect(status().isConflict());

        authorized(post("/api/v1/kyc/{id}/start-review", kycId), customer)
                .andExpect(status().isForbidden());

        authorized(post("/api/v1/kyc/{id}/start-review", kycId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        authorized(post("/api/v1/kyc/{id}/approve", kycId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        authorized(get("/api/v1/kyc"), customer)
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        // VERIFIED is final for the customer.
        verifyBvn(customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_KYC_STATE"));
    }

    @Test
    void shouldRejectKycUnderReview() throws Exception {

        String customer = registerAndLogin("reject@example.com", "08011111113");
        String admin = registerAdmin("admin2@example.com", "08011111114");
        String kycId = createKycUnderReview(customer, admin);

        authorized(reviewDecision("/api/v1/kyc/{id}/reject", kycId, ""), admin)
                .andExpect(status().isBadRequest());

        authorized(
                reviewDecision(
                        "/api/v1/kyc/{id}/reject",
                        kycId,
                        "Identity document could not be validated"
                ),
                admin
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        authorized(get("/api/v1/kyc"), customer)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewReason")
                        .value("Identity document could not be validated"));

        // REJECTED is final.
        authorized(post("/api/v1/kyc/start"), customer)
                .andExpect(status().isConflict());
    }

    @Test
    void shouldLetCustomerResubmitAfterInformationRequest() throws Exception {

        String customer = registerAndLogin("more@example.com", "08011111115");
        String admin = registerAdmin("admin3@example.com", "08011111116");
        String kycId = createKycUnderReview(customer, admin);

        authorized(
                reviewDecision(
                        "/api/v1/kyc/{id}/request-information",
                        kycId,
                        "Please provide a clearer proof of address"
                ),
                admin
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADDITIONAL_INFO_REQUIRED"));

        authorized(get("/api/v1/kyc"), customer)
                .andExpect(jsonPath("$.reviewReason")
                        .value("Please provide a clearer proof of address"));

        // The customer has to resume before changing anything.
        uploadDocument(customer, "PROOF_OF_ADDRESS")
                .andExpect(status().isConflict());

        authorized(post("/api/v1/kyc/start"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        uploadDocument(customer, "PROOF_OF_ADDRESS")
                .andExpect(status().isCreated());

        authorized(post("/api/v1/kyc/submit"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        authorized(post("/api/v1/kyc/{id}/start-review", kycId), admin)
                .andExpect(status().isOk());

        authorized(post("/api/v1/kyc/{id}/approve", kycId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.reviewReason").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kyc_documents", Integer.class
        )).isEqualTo(2);
    }

    @Test
    void reviewerShouldNotApproveOwnKyc() throws Exception {

        String admin = registerAdmin("self@example.com", "08011111117");
        String otherAdmin = registerAdmin("other@example.com", "08011111125");
        String kycId = createKycUnderReview(admin, otherAdmin);

        authorized(post("/api/v1/kyc/{id}/approve", kycId), admin)
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnBadGatewayAndRecordNothingWhenProviderIsDown()
            throws Exception {

        String customer = registerAndLogin("down@example.com", "08011111118");

        startKyc(customer);

        when(kycProvider.verifyBvn(any()))
                .thenThrow(new KycProviderException("Dojah returned HTTP 424"));

        verifyBvn(customer)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("KYC_PROVIDER_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kyc_verifications", Integer.class
        )).isZero();

        authorized(get("/api/v1/kyc/status"), customer)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void shouldBlockBvnChecksAfterThreeFailures() throws Exception {

        String customer = registerAndLogin("limit@example.com", "08011111120");

        startKyc(customer);

        when(kycProvider.verifyBvn(any()))
                .thenReturn(providerResult(VerificationResult.FAILED, "mismatch"));

        for (int remaining = 2; remaining >= 0; remaining--) {
            verifyBvn(customer)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("FAILED"))
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }

        String retryAfter =
                verifyBvn(customer)
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error")
                                .value("BVN_ATTEMPT_LIMIT_EXCEEDED"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Retry-After");

        // Roughly 24h until the first failure leaves the window.
        assertThat(Long.parseLong(retryAfter))
                .isBetween(86_000L, 86_400L);

        // The 4th request never reached the provider and was not recorded.
        verify(kycProvider, times(3)).verifyBvn(any());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kyc_verifications", Integer.class
        )).isEqualTo(3);
    }

    @Test
    void adminShouldResetBvnAttemptLimitWithoutErasingHistory()
            throws Exception {

        String customer = registerAndLogin("reset@example.com", "08011111122");

        String kycId = startKyc(customer);

        when(kycProvider.verifyBvn(any()))
                .thenReturn(providerResult(VerificationResult.FAILED, "mismatch"));

        for (int i = 0; i < 3; i++) {
            verifyBvn(customer).andExpect(status().isOk());
        }

        verifyBvn(customer).andExpect(status().isTooManyRequests());

        // Customers cannot lift their own limit.
        authorized(post("/api/v1/kyc/{id}/bvn-attempts/reset", kycId), customer)
                .andExpect(status().isForbidden());

        String admin = registerAdmin("resetter@example.com", "08011111123");
        String adminId =
                JsonPath.read(
                        authorized(get("/api/v1/customers/me"), admin)
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        // Paging: 3 attempts in pages of 2. The summary is the same on
        // both pages even though page 1 holds only the oldest attempt.
        authorized(
                get("/api/v1/kyc/{id}/bvn-attempts", kycId)
                        .param("size", "2"),
                admin
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.hasNext").value(true))
                .andExpect(jsonPath("$.limited").value(true))
                .andExpect(jsonPath("$.failedAttemptsCounted").value(3));

        authorized(
                get("/api/v1/kyc/{id}/bvn-attempts", kycId)
                        .param("page", "1")
                        .param("size", "2"),
                admin
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.limited").value(true))
                .andExpect(jsonPath("$.failedAttemptsCounted").value(3));

        authorized(
                get("/api/v1/kyc/{id}/bvn-attempts", kycId)
                        .param("size", "500"),
                admin
        )
                .andExpect(status().isBadRequest());

        // Customers cannot see the admin view either.
        authorized(get("/api/v1/kyc/{id}/bvn-attempts", kycId), customer)
                .andExpect(status().isForbidden());

        // What the admin sees before resetting: limited, 3 counted.
        authorized(get("/api/v1/kyc/{id}/bvn-attempts", kycId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limited").value(true))
                .andExpect(jsonPath("$.failedAttemptsCounted").value(3))
                .andExpect(jsonPath("$.remainingAttempts").value(0))
                .andExpect(jsonPath("$.retryAfter").exists())
                .andExpect(jsonPath("$.resetAt").doesNotExist())
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[*].countsTowardLimit")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is(true))));

        authorized(post("/api/v1/kyc/{id}/bvn-attempts/reset", kycId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycId").value(kycId))
                .andExpect(jsonPath("$.remainingAttempts").value(3))
                .andExpect(jsonPath("$.resetBy").value(adminId))
                .andExpect(jsonPath("$.resetAt").exists());

        // After the reset: history kept, but none of it counts any more.
        authorized(get("/api/v1/kyc/{id}/bvn-attempts", kycId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limited").value(false))
                .andExpect(jsonPath("$.failedAttemptsCounted").value(0))
                .andExpect(jsonPath("$.remainingAttempts").value(3))
                .andExpect(jsonPath("$.retryAfter").doesNotExist())
                .andExpect(jsonPath("$.resetBy").value(adminId))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[*].countsTowardLimit")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is(false))));

        // Fresh attempts: the next failure leaves 2.
        verifyBvn(customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAttempts").value(2));

        // Nothing was deleted: 3 old failures + 1 new one, and the reset
        // is recorded against the admin.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kyc_verifications WHERE result = 'FAILED'",
                Integer.class
        )).isEqualTo(4);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT bvn_attempts_reset_by::text FROM kyc_profiles WHERE id = ?::uuid",
                String.class,
                kycId
        )).isEqualTo(adminId);
    }

    @Test
    void adminShouldNotResetOwnBvnAttemptLimit() throws Exception {

        String admin = registerAdmin("selfreset@example.com", "08011111124");

        String kycId =
                JsonPath.read(
                        authorized(get("/api/v1/kyc"), admin)
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        authorized(post("/api/v1/kyc/{id}/bvn-attempts/reset", kycId), admin)
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT bvn_attempts_reset_at FROM kyc_profiles WHERE id = ?::uuid",
                java.sql.Timestamp.class,
                kycId
        )).isNull();
    }

    @Test
    void concurrentBvnChecksShouldNotExceedTheLimit() throws Exception {

        String customer = registerAndLogin("race@example.com", "08011111121");

        startKyc(customer);

        // A slow provider widens the window in which requests could race.
        when(kycProvider.verifyBvn(any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return providerResult(VerificationResult.FAILED, "mismatch");
                });

        ExecutorService executor = Executors.newFixedThreadPool(6);

        try {
            List<Future<Integer>> responses = new ArrayList<>();

            for (int i = 0; i < 6; i++) {
                responses.add(executor.submit(() ->
                        verifyBvn(customer).andReturn().getResponse().getStatus()
                ));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : responses) {
                statuses.add(response.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses)
                    .filteredOn(code -> code == 200)
                    .hasSize(3);

            assertThat(statuses)
                    .filteredOn(code -> code == 429)
                    .hasSize(3);

        } finally {
            executor.shutdownNow();
        }

        verify(kycProvider, times(3)).verifyBvn(any());
    }

    @Test
    void shouldRequireAuthenticationForKyc() throws Exception {

        mockMvc.perform(post("/api/v1/kyc"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/kyc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRequirePassedBvnAndDocumentBeforeSubmit() throws Exception {

        String customer = registerAndLogin("incomplete@example.com", "08011111126");
        startKyc(customer);

        authorized(post("/api/v1/kyc/submit"), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KYC_INCOMPLETE"))
                .andExpect(jsonPath("$.missing[0]").value("passed BVN or NIN verification"))
                .andExpect(jsonPath("$.missing[1]").value("identity document"));

        // A FAILED check does not count.
        when(kycProvider.verifyBvn(any()))
                .thenReturn(providerResult(VerificationResult.FAILED, "mismatch"))
                .thenReturn(providerResult(VerificationResult.PASSED, null));

        verifyBvn(customer).andExpect(jsonPath("$.result").value("FAILED"));
        uploadDocument(customer, "NATIONAL_ID").andExpect(status().isCreated());

        authorized(post("/api/v1/kyc/submit"), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0]").value("passed BVN or NIN verification"));

        verifyBvn(customer).andExpect(jsonPath("$.result").value("PASSED"));

        authorized(post("/api/v1/kyc/submit"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void registrationShouldCreateTheOnlyKycProfile() throws Exception {

        String customer = registerAndLogin("once@example.com", "08011111119");

        authorized(get("/api/v1/kyc/status"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));

        authorized(post("/api/v1/kyc"), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KYC_ALREADY_EXISTS"));
    }

    /**
     * Creates and starts the caller's KYC; returns its id.
     */
    private String startKyc(String accessToken) throws Exception {

        String kycId =
                JsonPath.read(
                        authorized(get("/api/v1/kyc"), accessToken)
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        authorized(post("/api/v1/kyc/start"), accessToken)
                .andExpect(status().isOk());

        return kycId;
    }

    /**
     * Customer starts, passes BVN, uploads a document and submits; the
     * reviewer then picks it up.
     */
    private String createKycUnderReview(String customer, String reviewer)
            throws Exception {

        String kycId = startKyc(customer);

        when(kycProvider.verifyBvn(any()))
                .thenReturn(providerResult(VerificationResult.PASSED, null));

        verifyBvn(customer)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        uploadDocument(customer, "NATIONAL_ID")
                .andExpect(status().isCreated());

        authorized(post("/api/v1/kyc/submit"), customer)
                .andExpect(status().isOk());

        authorized(post("/api/v1/kyc/{id}/start-review", kycId), reviewer)
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        return kycId;
    }

    private MockHttpServletRequestBuilder uploadDocument(String documentType) {

        return multipart("/api/v1/kyc/documents")
                .file(new MockMultipartFile(
                        "file",
                        "document.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        PDF
                ))
                .param("documentType", documentType);
    }

    private ResultActions uploadDocument(String accessToken, String documentType)
            throws Exception {

        return authorized(uploadDocument(documentType), accessToken);
    }

    private MockHttpServletRequestBuilder reviewDecision(
            String path,
            String kycId,
            String reason
    ) {

        return post(path, kycId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"%s\"}".formatted(reason));
    }

    private ResultActions verifyBvn(String accessToken) throws Exception {

        return authorized(
                post("/api/v1/kyc/bvn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bvn": "22222222222",
                                  "firstName": "Esther",
                                  "lastName": "Test",
                                  "dateOfBirth": "1995-01-01"
                                }
                                """),
                accessToken
        );
    }

    private ResultActions authorized(
            MockHttpServletRequestBuilder request,
            String accessToken
    ) throws Exception {

        return mockMvc.perform(
                request.header("Authorization", "Bearer " + accessToken)
        );
    }

    private KycProviderResult providerResult(
            VerificationResult result,
            String reason
    ) {
        return new KycProviderResult("DOJAH", "ref-" + UUID.randomUUID(), result, reason);
    }

    private String registerAdmin(String email, String phone) throws Exception {

        String customerId = register(email, phone);

        transactionTemplate.executeWithoutResult(status -> {
            Customer customer =
                    customerRepository
                            .findById(UUID.fromString(customerId))
                            .orElseThrow();
            customer.assignRole(
                    roleRepository.findByName(RoleName.ADMIN).orElseThrow()
            );
        });

        return login(email);
    }

    private String registerAndLogin(String email, String phone) throws Exception {

        register(email, phone);

        return login(email);
    }

    private String register(String email, String phone) throws Exception {

        return JsonPath.read(
                mockMvc.perform(
                                post("/api/v1/customers")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                    "firstName": "Esther",
                                                    "lastName": "Test",
                                                    "email": "%s",
                                                    "countryCode": "NG",
                                                    "phoneNumber": "%s",
                                                    "password": "Password123"
                                                }
                                                """.formatted(email, phone))
                        )
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
    }

    private String login(String email) throws Exception {

        return JsonPath.read(
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                    "email": "%s",
                                                    "password": "Password123"
                                                }
                                                """.formatted(email))
                        )
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.accessToken"
        );
    }
}
