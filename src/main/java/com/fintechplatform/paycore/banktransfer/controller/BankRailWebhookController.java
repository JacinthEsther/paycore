package com.fintechplatform.paycore.banktransfer.controller;

import com.fintechplatform.paycore.banktransfer.dto.InboundTransferAck;
import com.fintechplatform.paycore.banktransfer.service.InboundWebhookService;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Where the bank rail reports money other banks sent to PayCore accounts.
 * Not behind a sign-in: the rail authenticates with the request signature
 * instead (401 without a valid one).
 *
 * 200 ACCEPTED with the ledger reference, also for a repeated delivery of
 * the same session; 422 REJECTED when the account cannot take the money,
 * so the rail returns it to the sending bank.
 */
@RestController
@RequestMapping("/api/v1/webhooks/bank-rail")
public class BankRailWebhookController {

    private final InboundWebhookService webhookService;

    public BankRailWebhookController(InboundWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/inbound")
    public ResponseEntity<InboundTransferAck> inbound(
            @RequestHeader(value = InboundWebhookService.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String body
    ) {

        try {
            return ResponseEntity.ok(InboundTransferAck.accepted(webhookService.receive(body, signature).reference()));
        } catch (InboundTransferRejectedException rejected) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(InboundTransferAck.rejected(rejected.getMessage()));
        }
    }
}
