package com.fintechplatform.paycore.globalexception;

import com.fintechplatform.paycore.account.exception.AccountAlreadyExistsException;
import com.fintechplatform.paycore.account.exception.AccountNotFoundException;
import com.fintechplatform.paycore.account.exception.AccountNumberUnavailableException;
import com.fintechplatform.paycore.account.exception.InvalidAccountStateException;
import com.fintechplatform.paycore.account.exception.KycVerificationRequiredException;
import com.fintechplatform.paycore.account.exception.UnsupportedCurrencyException;
import com.fintechplatform.paycore.authorization.exception.RoleAlreadyAssignedException;
import com.fintechplatform.paycore.authorization.exception.RoleNotAssignedException;
import com.fintechplatform.paycore.authorization.exception.RoleNotFoundException;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.exception.DuplicateCustomerException;
import com.fintechplatform.paycore.customer.exception.InvalidCustomerStateException;
import com.fintechplatform.paycore.customer.exception.InvalidPhoneNumberException;
import com.fintechplatform.paycore.identity.exception.IdentityDisabledException;
import com.fintechplatform.paycore.identity.exception.InvalidCredentialsException;
import com.fintechplatform.paycore.identity.exception.InvalidRefreshTokenException;
import com.fintechplatform.paycore.identity.exception.LoginSessionNotFoundException;
import com.fintechplatform.paycore.identity.exception.RefreshTokenReuseException;
import com.fintechplatform.paycore.identity.exception.SessionTimedOutException;
import com.fintechplatform.paycore.customer.exception.EmailAlreadyVerifiedException;
import com.fintechplatform.paycore.customer.exception.InvalidVerificationTokenException;
import com.fintechplatform.paycore.customer.exception.VerificationEmailRateLimitException;
import com.fintechplatform.paycore.identity.exception.GoogleSignInDisabledException;
import com.fintechplatform.paycore.identity.exception.InvalidGoogleTokenException;
import com.fintechplatform.paycore.ledger.exception.CurrencyMismatchException;
import com.fintechplatform.paycore.ledger.exception.IdempotencyConflictException;
import com.fintechplatform.paycore.banktransfer.exception.BankTransfersDisabledException;
import com.fintechplatform.paycore.banktransfer.exception.BeneficiaryNotFoundException;
import com.fintechplatform.paycore.banktransfer.exception.InvalidWebhookSignatureException;
import com.fintechplatform.paycore.banktransfer.exception.SimulatorLimitExceededException;
import com.fintechplatform.paycore.banktransfer.exception.UnknownBankException;
import com.fintechplatform.paycore.banktransfer.rail.BankRailException;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import com.fintechplatform.paycore.operations.exception.DuplicateReversalRequestException;
import com.fintechplatform.paycore.operations.exception.FourEyesViolationException;
import com.fintechplatform.paycore.operations.exception.OperationsRequestNotFoundException;
import com.fintechplatform.paycore.operations.exception.RequestAlreadyDecidedException;
import com.fintechplatform.paycore.funding.exception.FundingDeclinedException;
import com.fintechplatform.paycore.funding.exception.FundingDisabledException;
import com.fintechplatform.paycore.funding.exception.FundingProviderException;
import com.fintechplatform.paycore.ledger.exception.FundingLimitExceededException;
import com.fintechplatform.paycore.ledger.exception.InsufficientFundsException;
import com.fintechplatform.paycore.ledger.exception.InvalidLedgerTransactionException;
import com.fintechplatform.paycore.ledger.exception.LedgerTransactionNotFoundException;
import com.fintechplatform.paycore.ledger.exception.TransactionNotReversibleException;
import com.fintechplatform.paycore.kyc.exception.BvnAttemptLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.BvnIpRateLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycDocumentException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycAlreadyExistsException;
import com.fintechplatform.paycore.kyc.exception.KycIncompleteException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.exception.KycProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateCustomerException.class)
    public ResponseEntity<Map<String, Object>>
    handleDuplicateCustomer(
            DuplicateCustomerException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "CUSTOMER_ALREADY_EXISTS",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<Map<String, Object>>
    handleCustomerNotFound(
            CustomerNotFoundException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 404,
                        "error", "CUSTOMER_NOT_FOUND",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(InvalidCustomerStateException.class)
    public ResponseEntity<Map<String, Object>>
    handleInvalidCustomerState(
            InvalidCustomerStateException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "INVALID_CUSTOMER_STATE",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(InvalidPhoneNumberException.class)
    public ResponseEntity<Map<String, Object>>
    handleInvalidPhoneNumber(
            InvalidPhoneNumberException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 400,
                        "error", "INVALID_PHONE_NUMBER",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>>
    handleIllegalArgument(
            IllegalArgumentException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 400,
                        "error", "INVALID_REQUEST",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(
            InvalidCredentialsException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 401,
                        "error", "INVALID_CREDENTIALS",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(IdentityDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleIdentityDisabled(
            IdentityDisabledException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 401,
                        "error", "IDENTITY_DISABLED",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(LoginSessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleLoginSessionNotFound(
            LoginSessionNotFoundException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 401,
                        "error", "INVALID_SESSION",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRefreshToken(
            InvalidRefreshTokenException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 401,
                        "error", "INVALID_REFRESH_TOKEN",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(KycNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKycNotFound(
            KycNotFoundException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 404,
                        "error", "KYC_NOT_FOUND",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(KycAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleKycAlreadyExists(
            KycAlreadyExistsException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "KYC_ALREADY_EXISTS",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(InvalidKycStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidKycState(
            InvalidKycStateException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "INVALID_KYC_STATE",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(KycIncompleteException.class)
    public ResponseEntity<Map<String, Object>> handleKycIncomplete(
            KycIncompleteException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "KYC_INCOMPLETE",
                        "message", exception.getMessage(),
                        "missing", exception.getMissing()
                ));
    }

    @ExceptionHandler(InvalidKycDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidKycDocument(
            InvalidKycDocumentException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 400,
                        "error", "INVALID_KYC_DOCUMENT",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(
            MaxUploadSizeExceededException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 413,
                        "error", "FILE_TOO_LARGE",
                        "message", "Uploaded file is too large"
                ));
    }

    @ExceptionHandler(BvnAttemptLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleBvnAttemptLimit(
            BvnAttemptLimitExceededException exception
    ) {
        return tooManyRequests(
                exception.getVerificationType() + "_ATTEMPT_LIMIT_EXCEEDED",
                exception.getMessage(),
                exception.getRetryAfter()
        );
    }

    @ExceptionHandler(BvnIpRateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleBvnIpRateLimit(
            BvnIpRateLimitExceededException exception
    ) {
        return tooManyRequests(
                exception.getVerificationType() + "_IP_RATE_LIMIT_EXCEEDED",
                exception.getMessage(),
                exception.getRetryAfter()
        );
    }

    private ResponseEntity<Map<String, Object>> tooManyRequests(
            String error,
            String message,
            Instant retryAfter
    ) {
        long retryAfterSeconds =
                Math.max(
                        1,
                        Duration.between(Instant.now(), retryAfter).toSeconds()
                );

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 429,
                        "error", error,
                        "message", message,
                        "retryAfter", retryAfter
                ));
    }

    /**
     * Provider details (status codes, credential problems) are logged,
     * not returned to the client.
     */
    @ExceptionHandler(KycProviderException.class)
    public ResponseEntity<Map<String, Object>> handleKycProvider(
            KycProviderException exception
    ) {
        log.warn("KYC provider failure: {}", exception.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 502,
                        "error", "KYC_PROVIDER_UNAVAILABLE",
                        "message", "Identity verification is temporarily unavailable"
                ));
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRoleNotFound(
            RoleNotFoundException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 404,
                        "error", "ROLE_NOT_FOUND",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(RoleAlreadyAssignedException.class)
    public ResponseEntity<Map<String, Object>> handleRoleAlreadyAssigned(
            RoleAlreadyAssignedException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "ROLE_ALREADY_ASSIGNED",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(RoleNotAssignedException.class)
    public ResponseEntity<Map<String, Object>> handleRoleNotAssigned(
            RoleNotAssignedException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 409,
                        "error", "ROLE_NOT_ASSIGNED",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(SessionTimedOutException.class)
    public ResponseEntity<Map<String, Object>> handleSessionTimedOut(
            SessionTimedOutException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 401,
                        "error", exception.getReason() == SessionTimedOutException.Reason.IDLE
                                ? "SESSION_IDLE_TIMEOUT"
                                : "SESSION_EXPIRED",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<Map<String, Object>> handleRefreshTokenReuse(
            RefreshTokenReuseException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 401,
                        "error", "REFRESH_TOKEN_REUSED",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNotFound(
            AccountNotFoundException exception
    ) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleAccountAlreadyExists(
            AccountAlreadyExistsException exception
    ) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(InvalidAccountStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidAccountState(
            InvalidAccountStateException exception
    ) {
        return error(HttpStatus.CONFLICT, "INVALID_ACCOUNT_STATE", exception.getMessage());
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedCurrency(
            UnsupportedCurrencyException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY", exception.getMessage());
    }

    @ExceptionHandler(KycVerificationRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleKycVerificationRequired(
            KycVerificationRequiredException exception
    ) {
        return error(HttpStatus.FORBIDDEN, "KYC_VERIFICATION_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(AccountNumberUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNumberUnavailable(
            AccountNumberUnavailableException exception
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ACCOUNT_NUMBER_UNAVAILABLE",
                "An account number could not be allocated; please try again"
        );
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(
            InsufficientFundsException exception
    ) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", exception.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleCurrencyMismatch(
            CurrencyMismatchException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH", exception.getMessage());
    }

    @ExceptionHandler(InvalidLedgerTransactionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidLedgerTransaction(
            InvalidLedgerTransactionException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", exception.getMessage());
    }

    @ExceptionHandler(LedgerTransactionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleLedgerTransactionNotFound(
            LedgerTransactionNotFoundException exception
    ) {
        return error(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidVerificationToken(
            InvalidVerificationTokenException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN", exception.getMessage());
    }

    @ExceptionHandler(EmailAlreadyVerifiedException.class)
    public ResponseEntity<Map<String, Object>> handleEmailAlreadyVerified(
            EmailAlreadyVerifiedException exception
    ) {
        return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_VERIFIED", exception.getMessage());
    }

    @ExceptionHandler(VerificationEmailRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleVerificationEmailRateLimit(
            VerificationEmailRateLimitException exception
    ) {
        return tooManyRequests(
                "VERIFICATION_EMAIL_RATE_LIMIT",
                exception.getMessage(),
                exception.getRetryAfter()
        );
    }

    @ExceptionHandler(InvalidGoogleTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidGoogleToken(
            InvalidGoogleTokenException exception
    ) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", exception.getMessage());
    }

    @ExceptionHandler(GoogleSignInDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleGoogleSignInDisabled(
            GoogleSignInDisabledException exception
    ) {
        return error(HttpStatus.NOT_FOUND, "GOOGLE_SIGN_IN_DISABLED", exception.getMessage());
    }

    @ExceptionHandler(TransactionNotReversibleException.class)
    public ResponseEntity<Map<String, Object>> handleTransactionNotReversible(
            TransactionNotReversibleException exception
    ) {
        return error(HttpStatus.CONFLICT, "TRANSACTION_NOT_REVERSIBLE", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(
            IdempotencyConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    @ExceptionHandler(FundingLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleFundingLimitExceeded(
            FundingLimitExceededException exception
    ) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "FUNDING_LIMIT_EXCEEDED", exception.getMessage());
    }

    /**
     * The provider's payment reference is returned so the customer can
     * quote it; nothing was credited.
     */
    @ExceptionHandler(FundingDeclinedException.class)
    public ResponseEntity<Map<String, Object>> handleFundingDeclined(
            FundingDeclinedException exception
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 422);
        body.put("error", "PAYMENT_DECLINED");
        body.put("message", exception.getMessage());
        body.put("providerReference", exception.getProviderReference());

        return ResponseEntity.unprocessableEntity().body(body);
    }

    @ExceptionHandler(FundingDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleFundingDisabled(
            FundingDisabledException exception
    ) {
        return error(HttpStatus.NOT_FOUND, "FUNDING_DISABLED", exception.getMessage());
    }

    /**
     * Provider details are logged, not returned to the client.
     */
    @ExceptionHandler(FundingProviderException.class)
    public ResponseEntity<Map<String, Object>> handleFundingProvider(
            FundingProviderException exception
    ) {
        log.warn("Funding provider failure: {}", exception.getMessage(), exception.getCause());

        return error(
                HttpStatus.BAD_GATEWAY,
                "PAYMENT_PROVIDER_UNAVAILABLE",
                "Payments are temporarily unavailable; you have not been charged"
        );
    }

    @ExceptionHandler(BankTransfersDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleBankTransfersDisabled(BankTransfersDisabledException exception) {
        return error(HttpStatus.NOT_FOUND, "BANK_TRANSFERS_DISABLED", exception.getMessage());
    }

    @ExceptionHandler(BeneficiaryNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleBeneficiaryNotFound(BeneficiaryNotFoundException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "BENEFICIARY_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(UnknownBankException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownBank(UnknownBankException exception) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_BANK", exception.getMessage());
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidWebhookSignature(InvalidWebhookSignatureException exception) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", exception.getMessage());
    }

    @ExceptionHandler(SimulatorLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleSimulatorLimit(SimulatorLimitExceededException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "SIMULATOR_LIMIT_EXCEEDED", exception.getMessage());
    }

    @ExceptionHandler(InboundTransferRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleInboundRejected(InboundTransferRejectedException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INBOUND_TRANSFER_REJECTED", exception.getMessage());
    }

    /** Rail details are logged, not returned. Nothing was debited. */
    @ExceptionHandler(BankRailException.class)
    public ResponseEntity<Map<String, Object>> handleBankRail(BankRailException exception) {
        log.warn("Bank rail failure: {}", exception.getMessage(), exception.getCause());

        return error(
                HttpStatus.BAD_GATEWAY,
                "BANK_RAIL_UNAVAILABLE",
                "Transfers to other banks are temporarily unavailable; no money has left your account"
        );
    }

    @ExceptionHandler(FourEyesViolationException.class)
    public ResponseEntity<Map<String, Object>> handleFourEyes(FourEyesViolationException exception) {
        return error(HttpStatus.FORBIDDEN, "FOUR_EYES_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(RequestAlreadyDecidedException.class)
    public ResponseEntity<Map<String, Object>> handleRequestDecided(RequestAlreadyDecidedException exception) {
        return error(HttpStatus.CONFLICT, "REQUEST_ALREADY_DECIDED", exception.getMessage());
    }

    @ExceptionHandler(OperationsRequestNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRequestNotFound(OperationsRequestNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DuplicateReversalRequestException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateReversal(DuplicateReversalRequestException exception) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_REVERSAL_REQUEST", exception.getMessage());
    }

    /**
     * Two requests changed the same record at once (optimistic locking).
     * Nothing was saved from the losing request; the client can reload and
     * retry.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentModification(
            ObjectOptimisticLockingFailureException exception
    ) {
        return error(
                HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION",
                "The record was changed by another request; reload and try again"
        );
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String error,
            String message
    ) {
        return ResponseEntity
                .status(status)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", status.value(),
                        "error", error,
                        "message", message
                ));
    }
}