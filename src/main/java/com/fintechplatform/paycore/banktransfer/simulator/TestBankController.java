package com.fintechplatform.paycore.banktransfer.simulator;

import com.fintechplatform.paycore.banktransfer.dto.SimulatedInboundRequest;
import com.fintechplatform.paycore.banktransfer.dto.TestBankAccountResponse;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/simulator/test-bank")
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class TestBankController {

    private final TestBankSimulator simulator;

    public TestBankController(TestBankSimulator simulator) {
        this.simulator = simulator;
    }

    @GetMapping("/account")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public TestBankAccountResponse account(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam UUID accountId
    ) {
        return simulator.account(currentUser.customerId(), accountId);
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public ResponseEntity<TransactionResponse> send(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody SimulatedInboundRequest request
    ) {

        TransactionResponse credit = simulator.send(currentUser.customerId(), request);

        return ResponseEntity
                .created(URI.create("/api/v1/ledger/transactions/" + credit.id()))
                .body(credit);
    }
}
