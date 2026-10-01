package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Tells the demo UI which shared admin account visitors can sign in with,
 * and which demo account they can send money to.
 * Publishing a password is deliberate here and only acceptable because
 * the endpoint exists solely in demo mode, on sandbox data. Without demo
 * mode the path does not exist.
 */
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

    public DemoController(
            DemoProperties properties,
            KycProvider kycProvider,
            DemoRecipientSeeder recipientSeeder,
            DemoOutbox outbox,
            CustomerRepository customerRepository,
            ObjectProvider<FundingProvider> fundingProviders,
            ObjectProvider<BankRail> rails
    ) {
        this.properties = properties;
        this.kycProvider = kycProvider;
        this.recipientSeeder = recipientSeeder;
        this.outbox = outbox;
        this.customerRepository = customerRepository;
        this.fundingProviders = fundingProviders;
        this.rails = rails;
    }

    @GetMapping
    public DemoInfoResponse info() {

        Optional<Account> recipient = recipientSeeder.findAccount();

        return new DemoInfoResponse(
                true,
                properties.normalizedAdminEmail(),
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
                DemoStaff.ALL.stream()
                        .map(staff -> new DemoStaffInfo(
                                staff.email(), staff.firstName() + " " + staff.lastName(), staff.duty()
                        ))
                        .toList()
        );
    }

    /**
     * The emails sent to the signed-in visitor's own address, newest first:
     * how a demo visitor with a made-up address opens their verification
     * link. Needs a sign-in (every path here but GET /api/v1/demo does).
     */
    @GetMapping("/outbox")
    public List<DemoOutbox.DemoEmail> outbox(@AuthenticationPrincipal CurrentUser currentUser) {

        return customerRepository
                .findById(currentUser.customerId())
                .map(customer -> outbox.emailsTo(customer.getEmail()))
                .orElse(List.of());
    }

    /**
     * @param kycProvider             which identity provider answers BVN/NIN
     *                                checks, so the UI can say when results
     *                                are simulated
     * @param recipientName           who the demo recipient is, or null
     *                                before it has been seeded
     * @param recipientAccountNumber  an active NGN account visitors can
     *                                transfer to, or null
     * @param fundingProvider         which payment provider takes customer
     *                                top-ups, or null if card top-ups are off
     * @param bankRailProvider        which rail carries transfers to and from
     *                                other banks, or null if none
     * @param operationsStaff         the shared maker and checker officers
     */
    public record DemoInfoResponse(
            boolean enabled,
            String adminEmail,
            String adminPassword,
            String kycProvider,
            String recipientName,
            String recipientAccountNumber,
            String fundingProvider,
            String bankRailProvider,
            List<DemoStaffInfo> operationsStaff
    ) {
    }

    /** A shared operations officer: signs in with the demo admin password. */
    public record DemoStaffInfo(String email, String name, String duty) {
    }
}
