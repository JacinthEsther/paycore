package com.fintechplatform.paycore.banktransfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintechplatform.paycore.banktransfer.config.RailProperties;
import com.fintechplatform.paycore.banktransfer.dto.InboundTransferNotification;
import com.fintechplatform.paycore.banktransfer.exception.BankTransfersDisabledException;
import com.fintechplatform.paycore.banktransfer.exception.InvalidWebhookSignatureException;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inbound transfer notifications from the bank rail. Only a notification
 * signed with the shared webhook secret is believed: anyone can reach the
 * URL, and an unsigned "money arrived" message would let them mint money.
 */
@Service
public class InboundWebhookService {

    public static final String SIGNATURE_HEADER = "X-PayCore-Signature";

    private final ObjectProvider<BankRail> rails;
    private final RailProperties properties;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public InboundWebhookService(
            ObjectProvider<BankRail> rails,
            RailProperties properties,
            LedgerService ledgerService,
            ObjectMapper objectMapper,
            Validator validator
    ) {
        this.rails = rails;
        this.properties = properties;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * Verifies the signature over the exact bytes received, then credits
     * the transfer (idempotently, by session id).
     */
    public TransactionResponse receive(String rawBody, String signature) {

        BankRail rail = rails.getIfAvailable();
        String secret = properties.getWebhookSecret();

        if (rail == null || secret == null || secret.isBlank()) {
            throw new BankTransfersDisabledException();
        }

        if (signature == null || !MessageDigest.isEqual(
                sign(secret, rawBody).getBytes(StandardCharsets.US_ASCII),
                signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new InvalidWebhookSignatureException();
        }

        InboundTransferNotification notification = parse(rawBody);

        return ledgerService.receiveInboundTransfer(
                new InboundTransfer(
                        rail.providerName(),
                        notification.sessionId(),
                        notification.destinationAccountNumber(),
                        notification.amount(),
                        notification.currency(),
                        new Counterparty(
                                notification.senderName(),
                                notification.senderBank(),
                                notification.senderAccountNumber()
                        ),
                        notification.narration()
                )
        );
    }

    /** Lower-case hex HMAC-SHA256 of the body: what the rail must send. */
    public static String sign(String secret, String body) {

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private InboundTransferNotification parse(String rawBody) {

        InboundTransferNotification notification;

        try {
            notification = objectMapper.readValue(rawBody, InboundTransferNotification.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed notification");
        }

        Set<ConstraintViolation<InboundTransferNotification>> violations = validator.validate(notification);

        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    violations.stream()
                            .map(ConstraintViolation::getMessage)
                            .sorted()
                            .collect(Collectors.joining("; "))
            );
        }

        return notification;
    }
}
