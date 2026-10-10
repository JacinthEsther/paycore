package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.banktransfer.dto.OutboundTransferRequest;
import com.fintechplatform.paycore.banktransfer.exception.InboundNotificationLostException;
import com.fintechplatform.paycore.funding.service.FundingService;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fintechplatform.paycore.banktransfer.dto.OutboundTransferResponse;
import com.fintechplatform.paycore.banktransfer.dto.SimulatedInboundRequest;
import com.fintechplatform.paycore.banktransfer.dto.TestBankAccountResponse;
import com.fintechplatform.paycore.banktransfer.service.BankTransferService;
import com.fintechplatform.paycore.banktransfer.simulator.TestBankSimulator;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.dto.VerifyBvnRequest;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.provider.SimulatedKycProvider;
import com.fintechplatform.paycore.kyc.provider.SimulatedKycProvider.SamplePerson;
import com.fintechplatform.paycore.kyc.service.KycDocumentService;
import com.fintechplatform.paycore.kyc.service.KycService;
import com.fintechplatform.paycore.kyc.service.KycTierService;
import com.fintechplatform.paycore.ledger.dto.request.TransferRequest;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoScenarioService {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioService.class);

    public static final List<String> SCENARIOS = List.of(
            "kyc-review", "tier-upgrade", "contact-update", "suspicious-activity",
            "stuck-transfer", "returned-transfer", "wrong-recipient", "new-hire", "customer-enquiry",
            "reconciliation-day"
    );

    private static final String REASON = "Developer Preview sample scenario";
    private static final int MAX_RUNS_PER_HOUR = 20;

    private final DemoCustomers customers;
    private final CustomerRepository customerRepository;
    private final KycService kycService;
    private final KycDocumentService kycDocumentService;
    private final KycTierService kycTierService;
    private final LedgerService ledgerService;
    private final ObjectProvider<SimulatedKycProvider> simulatedKyc;
    private final ObjectProvider<TestBankSimulator> testBank;
    private final ObjectProvider<BankTransferService> bankTransfers;
    private final ObjectProvider<FundingService> funding;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Deque<Instant>> runs = new ConcurrentHashMap<>();

    public DemoScenarioService(
            DemoCustomers customers,
            CustomerRepository customerRepository,
            KycService kycService,
            KycDocumentService kycDocumentService,
            KycTierService kycTierService,
            LedgerService ledgerService,
            ObjectProvider<SimulatedKycProvider> simulatedKyc,
            ObjectProvider<TestBankSimulator> testBank,
            ObjectProvider<BankTransferService> bankTransfers,
            ObjectProvider<FundingService> funding
    ) {
        this.customers = customers;
        this.customerRepository = customerRepository;
        this.kycService = kycService;
        this.kycDocumentService = kycDocumentService;
        this.kycTierService = kycTierService;
        this.ledgerService = ledgerService;
        this.simulatedKyc = simulatedKyc;
        this.testBank = testBank;
        this.bankTransfers = bankTransfers;
        this.funding = funding;
    }

    public DemoScenarioResult create(String scenario, UUID staffId) {

        if (!SCENARIOS.contains(scenario)) {
            throw new DemoScenarioException(HttpStatus.NOT_FOUND, "UNKNOWN_SCENARIO", "There is no scenario " + scenario);
        }

        if (!List.of("kyc-review", "tier-upgrade", "new-hire").contains(scenario)) {
            requireBankTransfers();
        }

        requireWithinRate(staffId);

        return switch (scenario) {
            case "kyc-review" -> kycReview();
            case "tier-upgrade" -> tierUpgrade();
            case "contact-update" -> contactUpdate();
            case "suspicious-activity" -> suspiciousActivity();
            case "stuck-transfer" -> outboundTransfer("stuck-transfer", "15000.96", "Payment for goods");
            case "returned-transfer" -> outboundTransfer("returned-transfer", "12000.93", "School fees");
            case "new-hire" -> newHire();
            case "customer-enquiry" -> customerEnquiry();
            case "reconciliation-day" -> reconciliationDay();
            default -> wrongRecipient();
        };
    }

    private DemoScenarioResult reconciliationDay() {

        BankTransferService transfers = requireBankTransfers();
        Customer customer = ready(3);
        Account account = customers.findOpenAccount(customer.getId()).orElseThrow();
        TestBankAccountResponse otherBank = testBank.getObject().account(customer.getId(), account.getId());

        fund(customer, account, "40000.00", "Salary");

        try {
            fund(customer, account, "25000.95", "Client payment");
        } catch (InboundNotificationLostException lost) {
            log.info("Sample notification lost on purpose: {}", lost.getMessage());
        }

        for (String amount : List.of("5000.00", "12000.93")) {
            transfers.send(customer.getId(), account.getId(), new OutboundTransferRequest(
                    otherBank.bankCode(),
                    otherBank.accountNumber(),
                    new BigDecimal(amount),
                    DemoCustomers.CURRENCY,
                    "Transfer",
                    "sample-" + UUID.randomUUID()
            ));
        }

        FundingService cards = funding.getIfAvailable();
        String cardBreaks = "";

        if (cards != null) {
            for (String amount : List.of("15000.00", "10000.94", "8000.92")) {
                cards.fund(customer.getId(), account.getId(), new FundAccountRequest(
                        new BigDecimal(amount), DemoCustomers.CURRENCY, "sample-" + UUID.randomUUID()
                ));
            }
            cardBreaks = " Card: NGN 10,000.94 charged NGN 1 too much; NGN 8,000.92 never settled.";
        }

        return result("reconciliation-day", customer, account, null,
                "Bank rail: NGN 25,000.95 arrived but PayCore was never told; NGN 12,000.93 was returned by the "
                        + "other bank." + cardBreaks);
    }

    private DemoScenarioResult customerEnquiry() {

        BankTransferService transfers = requireBankTransfers();
        Customer customer = ready(2);
        Account account = customers.findOpenAccount(customer.getId()).orElseThrow();
        Account recipient =
                customerRepository
                        .findByEmail(DemoRecipientSeeder.EMAIL)
                        .flatMap(found -> customers.findOpenAccount(found.getId()))
                        .orElseThrow();

        fund(customer, account, "85000.00", "Salary");

        ledgerService.transfer(customer.getId(), account.getId(), new TransferRequest(
                recipient.getAccountNumber(),
                new BigDecimal("12500.00"),
                DemoCustomers.CURRENCY,
                "sample-" + UUID.randomUUID(),
                "Rent share"
        ));

        TestBankAccountResponse otherBank = testBank.getObject().account(customer.getId(), account.getId());

        OutboundTransferResponse sent = transfers.send(customer.getId(), account.getId(), new OutboundTransferRequest(
                otherBank.bankCode(),
                otherBank.accountNumber(),
                new BigDecimal("7000.00"),
                DemoCustomers.CURRENCY,
                "Savings",
                "sample-" + UUID.randomUUID()
        ));

        return result("customer-enquiry", customer, account, sent.paymentReference(),
                "Received a NGN 85,000 salary, sent NGN 12,500 rent share to Tunde Bakare and NGN 7,000 to their "
                        + "Test Bank savings. They think they should have more money than the app shows.");
    }

    private DemoScenarioResult newHire() {

        Customer hire = register(person());

        return new DemoScenarioResult(
                "new-hire",
                hire.getFirstName() + " " + hire.getLastName(),
                hire.getEmail(),
                null,
                null,
                "Joined the operations team today and has signed in once. They have no staff role yet."
        );
    }

    private DemoScenarioResult kycReview() {

        if (simulatedKyc.getIfAvailable() == null) {
            throw unavailable("Sample KYC needs the simulated KYC provider (PAYCORE_KYC_PROVIDER=simulated).");
        }

        SamplePerson person = person();
        Customer customer = register(person);

        kycService.startKyc(customer.getId());
        kycService.verifyBvn(
                customer.getId(),
                new VerifyBvnRequest(person.bvn(), person.firstName(), person.lastName(), person.dateOfBirth()),
                "127.0.0.1"
        );
        kycDocumentService.upload(
                customer.getId(),
                KycDocumentType.NATIONAL_ID,
                person.nin(),
                DemoDocuments.pdf("National Identity Card", List.of(
                        "Surname: " + person.lastName().toUpperCase(Locale.ROOT),
                        "First name: " + person.firstName(),
                        "Date of birth: " + person.dateOfBirth(),
                        "NIN: " + person.nin()
                ))
        );
        kycService.submitKyc(customer.getId());

        Account account = customers.openAccount(customer);

        return result("kyc-review", customer, account, null,
                "Submitted KYC with a passed BVN check and a national ID; the account is waiting to be activated.");
    }

    private DemoScenarioResult tierUpgrade() {

        SamplePerson person = person();
        Customer customer = ready(person, 2);

        kycTierService.requestAddressUpgrade(
                customer.getId(),
                DemoDocuments.pdf("Utility Bill - Ikeja Electric", List.of(
                        "Customer: " + person.firstName() + " " + person.lastName(),
                        "Address: 14 Adeniyi Jones Avenue, Ikeja, Lagos",
                        "Billing period: last month",
                        "Amount due: NGN 18,450.00"
                ))
        );

        return result("tier-upgrade", customer, customers.findOpenAccount(customer.getId()).orElseThrow(), null,
                "A tier 2 customer uploaded a utility bill to move to tier 3.");
    }

    private DemoScenarioResult contactUpdate() {

        Customer customer = ready(1);
        Account account = customers.findOpenAccount(customer.getId()).orElseThrow();
        fund(customer, account, "20000.00", "Transfer from savings");

        return result("contact-update", customer, account, null,
                "An everyday customer with a funded account. They have a new phone number.");
    }

    private DemoScenarioResult suspiciousActivity() {

        Customer customer = ready(3);
        Account account = customers.findOpenAccount(customer.getId()).orElseThrow();

        List<String> narrations = List.of("Contract payment", "Refund", "Business", "Support", "Contract payment");
        TransactionResponse last = null;
        for (String narration : narrations) {
            last = fund(customer, account, "180000.00", narration);
        }

        return result("suspicious-activity", customer, account, last.reference(),
                "Five credits of NGN 180,000 from other banks within a few minutes, on a new account.");
    }

    private DemoScenarioResult outboundTransfer(String scenario, String amount, String narration) {

        BankTransferService transfers = requireBankTransfers();
        Customer customer = ready(2);
        Account account = customers.findOpenAccount(customer.getId()).orElseThrow();

        fund(customer, account, "50000.00", "Salary");

        TestBankAccountResponse otherBank = testBank.getObject().account(customer.getId(), account.getId());

        OutboundTransferResponse sent = transfers.send(customer.getId(), account.getId(), new OutboundTransferRequest(
                otherBank.bankCode(),
                otherBank.accountNumber(),
                new BigDecimal(amount),
                DemoCustomers.CURRENCY,
                narration,
                "sample-" + UUID.randomUUID()
        ));

        String summary = scenario.equals("stuck-transfer")
                ? "Sent NGN 15,000.96 to Test Bank. The bank took the money but will never confirm it, so the payment is stuck."
                : "Sent NGN 12,000.93 to Test Bank. It succeeded, then the beneficiary bank returned it; only reconciliation shows that.";

        return result(scenario, customer, account, sent.paymentReference(), summary);
    }

    private DemoScenarioResult wrongRecipient() {

        Customer sender = ready(2);
        Account senderAccount = customers.findOpenAccount(sender.getId()).orElseThrow();
        Customer stranger = ready(2);
        Account strangerAccount = customers.findOpenAccount(stranger.getId()).orElseThrow();

        fund(sender, senderAccount, "60000.00", "Salary");

        TransactionResponse sent = ledgerService.transfer(sender.getId(), senderAccount.getId(), new TransferRequest(
                strangerAccount.getAccountNumber(),
                new BigDecimal("25000.00"),
                DemoCustomers.CURRENCY,
                "sample-" + UUID.randomUUID(),
                "Rent"
        ));

        return result("wrong-recipient", sender, senderAccount, sent.reference(),
                "Sent NGN 25,000 to " + stranger.getFirstName() + " " + stranger.getLastName()
                        + " (" + strangerAccount.getAccountNumber() + ") by mistake. "
                        + stranger.getFirstName() + " has agreed to return it.");
    }

    private Customer ready(int tier) {
        return ready(person(), tier);
    }

    private Customer ready(SamplePerson person, int tier) {

        Customer customer = register(person);
        customers.verifyKyc(customer, tier);
        Customer compliance =
                customerRepository.findByEmail(DemoStaff.COMPLIANCE.email()).orElseThrow();
        customers.ensureActive(customers.openAccount(customer), compliance.getId(), REASON);
        return customer;
    }

    private TransactionResponse fund(Customer customer, Account account, String amount, String narration) {

        TestBankSimulator bank = testBank.getIfAvailable();

        if (bank == null) {
            throw unavailable("Sample payments need the simulated bank rail (PAYCORE_RAILS_PROVIDER=simulated).");
        }

        return bank.send(customer.getId(), new SimulatedInboundRequest(
                account.getAccountNumber(), new BigDecimal(amount), narration
        ));
    }

    private BankTransferService requireBankTransfers() {

        BankTransferService transfers = bankTransfers.getIfAvailable();

        if (transfers == null || testBank.getIfAvailable() == null) {
            throw unavailable("Sample payments need the simulated bank rail (PAYCORE_RAILS_PROVIDER=simulated).");
        }

        return transfers;
    }

    private Customer register(SamplePerson person) {

        byte[] bytes = new byte[3];
        random.nextBytes(bytes);
        String tag = HexFormat.of().formatHex(bytes);
        String email = (person.firstName() + "." + person.lastName() + "." + tag + "@sample.paycore.demo")
                .toLowerCase(Locale.ROOT);
        String phone = "080" + String.format("%08d", random.nextInt(100_000_000));

        return customers.findOrRegister(person.firstName(), person.lastName(), email, phone, REASON);
    }

    private SamplePerson person() {
        List<SamplePerson> people = SimulatedKycProvider.SAMPLE_PEOPLE;
        return people.get(random.nextInt(people.size()));
    }

    private void requireWithinRate(UUID staffId) {

        Instant now = Instant.now();
        Deque<Instant> recent = runs.computeIfAbsent(staffId, id -> new ArrayDeque<>());

        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
                recent.pollFirst();
            }
            if (recent.size() >= MAX_RUNS_PER_HOUR) {
                throw new DemoScenarioException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_SCENARIOS",
                        "You can create " + MAX_RUNS_PER_HOUR + " sample situations an hour. Try again later.");
            }
            recent.addLast(now);
        }
    }

    private static DemoScenarioException unavailable(String message) {
        return new DemoScenarioException(HttpStatus.CONFLICT, "SCENARIO_UNAVAILABLE", message);
    }

    private static DemoScenarioResult result(
            String scenario, Customer customer, Account account, String reference, String summary
    ) {
        return new DemoScenarioResult(
                scenario,
                customer.getFirstName() + " " + customer.getLastName(),
                customer.getEmail(),
                account.getAccountNumber(),
                reference,
                summary
        );
    }

    public record DemoScenarioResult(
            String scenario,
            String customerName,
            String customerEmail,
            String accountNumber,
            String reference,
            String summary
    ) {
    }

    public static class DemoScenarioException extends RuntimeException {

        private final HttpStatus status;
        private final String error;

        public DemoScenarioException(HttpStatus status, String error, String message) {
            super(message);
            this.status = status;
            this.error = error;
        }

        public HttpStatus status() {
            return status;
        }

        public String error() {
            return error;
        }
    }
}
