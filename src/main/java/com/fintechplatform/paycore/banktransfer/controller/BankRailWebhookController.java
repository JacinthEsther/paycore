package com.fintechplatform.paycore.banktransfer.controller;

import com.fintechplatform.paycore.banktransfer.dto.InboundTransferAck;
import com.fintechplatform.paycore.banktransfer.service.InboundWebhookService;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
