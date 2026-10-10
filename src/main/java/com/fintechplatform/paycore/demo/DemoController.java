package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoController {

    private final DemoProperties properties;
    private final KycProvider kycProvider;
    private final DemoRecipientSeeder recipientSeeder;
    private final DemoOutbox outbox;
    private final CustomerRepository customerRepository;
    private final ObjectProvider<FundingProvider> fundingProviders;
    private final ObjectProvider<BankRail> rails;
    private final DemoScenarioService scenarios;

    public DemoController(
            DemoProperties properties,
            KycProvider kycProvider,
            DemoRecipientSeeder recipientSeeder,
            DemoOutbox outbox,
            CustomerRepository customerRepository,
            ObjectProvider<FundingProvider> fundingProviders,
            ObjectProvider<BankRail> rails,
            DemoScenarioService scenarios
    ) {
        this.properties = properties;
        this.kycProvider = kycProvider;
        this.recipientSeeder = recipientSeeder;
        this.outbox = outbox;
        this.customerRepository = customerRepository;
        this.fundingProviders = fundingProviders;
        this.rails = rails;
        this.scenarios = scenarios;
    }

    @GetMapping
    public DemoInfoResponse info() {

        Optional<Account> recipient = recipientSeeder.findAccount();

        List<DemoStaffInfo> staff = new ArrayList<>();

        DemoStaff.ALL.forEach(member -> staff.add(new DemoStaffInfo(
                member.email(), member.firstName() + " " + member.lastName(),
                member.role(), member.title(), member.duty()
        )));

        staff.add(new DemoStaffInfo(
                properties.normalizedAdminEmail(),
                properties.getAdminFirstName() + " " + properties.getAdminLastName(),
                RoleName.ADMIN, DemoStaff.ADMIN_TITLE, DemoStaff.ADMIN_DUTY
        ));

        return new DemoInfoResponse(
                true,
                properties.getAdminPassword(),
                kycProvider.providerName(),
                recipient.map(account -> "Tunde Bakare (demo recipient)").orElse(null),
                recipient.map(Account::getAccountNumber).orElse(null),
                Optional.ofNullable(fundingProviders.getIfAvailable())
                        .map(FundingProvider::providerName)
                        .orElse(null),
                Optional.ofNullable(rails.getIfAvailable())
                        .map(BankRail::providerName)
                        .orElse(null),
                staff
        );
    }

    @PostMapping("/scenarios/{scenario}")
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public DemoScenarioService.DemoScenarioResult createScenario(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable String scenario
    ) {
        return scenarios.create(scenario, currentUser.customerId());
    }

    @ExceptionHandler(DemoScenarioService.DemoScenarioException.class)
    public ResponseEntity<Map<String, Object>> handleScenario(DemoScenarioService.DemoScenarioException exception) {
        return ResponseEntity
                .status(exception.status())
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", exception.status().value(),
                        "error", exception.error(),
                        "message", exception.getMessage()
                ));
    }

    @GetMapping("/outbox")
    public List<DemoOutbox.DemoEmail> outbox(@AuthenticationPrincipal CurrentUser currentUser) {

        return customerRepository
                .findById(currentUser.customerId())
                .map(customer -> outbox.emailsTo(customer.getEmail()))
                .orElse(List.of());
    }

    public record DemoInfoResponse(
            boolean enabled,
            String staffPassword,
            String kycProvider,
            String recipientName,
            String recipientAccountNumber,
            String fundingProvider,
            String bankRailProvider,
            List<DemoStaffInfo> staff
    ) {
    }

    public record DemoStaffInfo(String email, String name, String role, String title, String duty) {
    }
}
