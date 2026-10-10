package com.fintechplatform.paycore.reconciliation.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.banktransfer.exception.BankTransfersDisabledException;
import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.banktransfer.rail.RailSettlementLine;
import com.fintechplatform.paycore.funding.exception.FundingDisabledException;
import com.fintechplatform.paycore.funding.provider.CardPaymentRecord;
import com.fintechplatform.paycore.funding.provider.CardSettlementLine;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.entity.LedgerEntry;
import com.fintechplatform.paycore.ledger.entity.LedgerTransaction;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import com.fintechplatform.paycore.ledger.repository.LedgerEntryRepository;
import com.fintechplatform.paycore.ledger.repository.LedgerTransactionRepository;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.enums.PaymentType;
import com.fintechplatform.paycore.payment.repository.PaymentRepository;
import com.fintechplatform.paycore.reconciliation.entity.ReconciliationItem;
import com.fintechplatform.paycore.reconciliation.entity.ReconciliationItem.Links;
import com.fintechplatform.paycore.reconciliation.entity.ReconciliationItem.Side;
import com.fintechplatform.paycore.reconciliation.entity.ReconciliationRun;
import com.fintechplatform.paycore.reconciliation.enums.ItemKind;
import com.fintechplatform.paycore.reconciliation.enums.ProviderKind;
import com.fintechplatform.paycore.reconciliation.enums.ReconciliationResult;
import com.fintechplatform.paycore.reconciliation.enums.ResolutionStatus;
import com.fintechplatform.paycore.reconciliation.exception.ReconciliationItemNotFoundException;
import com.fintechplatform.paycore.reconciliation.exception.ReconciliationRunNotFoundException;
import com.fintechplatform.paycore.reconciliation.repository.ReconciliationItemRepository;
import com.fintechplatform.paycore.reconciliation.repository.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    static final Duration LOOKUP_MARGIN = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationItemRepository itemRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final ObjectProvider<BankRail> rails;
    private final ObjectProvider<FundingProvider> fundingProviders;
    private final ZoneId timeZone;

    public ReconciliationService(
            ReconciliationRunRepository runRepository,
            ReconciliationItemRepository itemRepository,
            PaymentRepository paymentRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            AccountRepository accountRepository,
            LedgerService ledgerService,
            ObjectProvider<BankRail> rails,
            ObjectProvider<FundingProvider> fundingProviders,
            @Value("${paycore.ledger.statement-time-zone:Africa/Lagos}") ZoneId timeZone
    ) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.rails = rails;
        this.fundingProviders = fundingProviders;
        this.timeZone = timeZone;
    }

    public LocalDate today() {
        return LocalDate.now(timeZone);
    }

    @Transactional
    public ReconciliationRun run(ProviderKind kind, LocalDate businessDate, UUID startedBy) {

        Instant from = businessDate.atStartOfDay(timeZone).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(timeZone).toInstant();

        Tally tally = switch (kind) {
            case BANK_RAIL -> reconcileRail(requireRail(), from, to);
            case CARD -> reconcileCard(requireFundingProvider(), from, to);
        };

        ReconciliationRun run = runRepository.saveAndFlush(new ReconciliationRun(
                kind, tally.provider, businessDate, from, to, startedBy,
                tally.providerLines, tally.paycoreRecords.size(),
                (int) tally.items.stream().filter(item -> !item.result().isBreak()).count(),
                (int) tally.items.stream().filter(item -> item.result().isBreak()).count(),
                tally.providerTotal, tally.paycoreTotal
        ));

        for (Draft draft : tally.items) {

            ReconciliationItem item = new ReconciliationItem(
                    run.getId(), draft.kind(), draft.reference(), draft.result(), draft.currency(),
                    draft.provider(), draft.paycore(), draft.links(), draft.detail()
            );

            if (draft.result().isBreak()) {
                carryOver(item);
            }

            itemRepository.save(item);
        }

        log.info("Reconciled {} {} for {}: {} matched, {} breaks",
                kind, tally.provider, businessDate, run.getMatched(), run.getBreaks());

        return run;
    }

    private void carryOver(ReconciliationItem item) {

        itemRepository
                .findFirstByKindAndExternalReferenceAndResultAndResolutionStatusOrderByResolvedAtDesc(
                        item.getKind(), item.getExternalReference(), item.getResult(), ResolutionStatus.RESOLVED
                )
                .ifPresent(earlier -> item.resolve(
                        earlier.getResolvedBy(),
                        "Resolved in an earlier run: " + earlier.getResolutionNote()
                ));
    }

    private Tally reconcileRail(BankRail rail, Instant from, Instant to) {

        String provider = rail.providerName();
        Tally tally = new Tally(provider);

        List<RailSettlementLine> report = rail.settlementReport(from.minus(LOOKUP_MARGIN), to.plus(LOOKUP_MARGIN));
        Map<String, RailSettlementLine> bySession = index(report, RailSettlementLine::sessionId);

        for (RailSettlementLine line : report) {

            if (line.createdAt().isBefore(from) || !line.createdAt().isBefore(to)) {
                continue;
            }

            tally.providerLines++;

            if (RailSettlementLine.SUCCESSFUL.equals(line.status())) {
                tally.providerTotal += line.amountMinor();
            }

            if (RailSettlementLine.OUTBOUND.equals(line.direction())) {
                outbound(tally, line, paymentRepository.findByProviderAndExternalReference(provider, line.sessionId()));
            } else {
                inbound(tally, line, transactionRepository.findByProviderAndProviderReference(provider, line.sessionId()));
            }
        }

        for (Payment payment : paymentRepository.findByTypeAndProviderAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                PaymentType.OUTBOUND_TRANSFER, provider, from, to)) {

            if (tally.seen(payment.getId())) {
                continue;
            }

            RailSettlementLine line = bySession.get(payment.getExternalReference());

            if (line != null) {
                outbound(tally, line, Optional.of(payment));
                continue;
            }

            String status = outboundStatus(payment);
            tally.paycoreRecords.add(payment.getId());

            if (status.equals(PaymentStatus.FAILED.name())) {
                continue;
            }

            countPaycore(tally, status, payment.getAmountMinor());

            tally.add(new Draft(
                    ItemKind.OUTBOUND, payment.getExternalReference(), ReconciliationResult.MISSING_PROVIDER,
                    payment.getCurrency(), Side.MISSING, new Side(payment.getAmountMinor(), status),
                    linksOf(payment),
                    "PayCore debited the customer for a transfer the rail has no record of. Check the payment's "
                            + "status; if the rail never had it, the money must go back to the customer."
            ));
        }

        for (LedgerTransaction credit : transactionRepository.findByTypeAndProviderAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                LedgerTransactionType.INBOUND_TRANSFER, provider, from, to)) {

            if (tally.seen(credit.getId())) {
                continue;
            }

            RailSettlementLine line = bySession.get(credit.getProviderReference());

            if (line != null) {
                inbound(tally, line, Optional.of(credit));
                continue;
            }

            LedgerEntry entry = creditEntry(credit);
            String status = inboundStatus(credit);
            tally.paycoreRecords.add(credit.getId());
            countPaycore(tally, status, entry.getAmountMinor());

            tally.add(new Draft(
                    ItemKind.INBOUND, credit.getProviderReference(), ReconciliationResult.MISSING_PROVIDER,
                    credit.getCurrency(), Side.MISSING, new Side(entry.getAmountMinor(), status),
                    new Links(null, credit.getId(), entry.getAccount().getId()),
                    "PayCore credited a customer for money the rail never delivered. The settlement bank account "
                            + "is short by this amount until it is explained or reversed."
            ));
        }

        return tally;
    }

    private void outbound(Tally tally, RailSettlementLine line, Optional<Payment> found) {

        if (found.isEmpty()) {
            tally.add(new Draft(
                    ItemKind.OUTBOUND, line.sessionId(), ReconciliationResult.MISSING_LOCAL, line.currency(),
                    new Side(line.amountMinor(), line.status()), Side.MISSING, Links.NONE,
                    "The rail carried a transfer from PayCore that PayCore has no payment for."
            ));
            return;
        }

        Payment payment = found.get();
        String status = outboundStatus(payment);
        tally.paycoreRecords.add(payment.getId());
        countPaycore(tally, status, payment.getAmountMinor());

        boolean railPaid = RailSettlementLine.SUCCESSFUL.equals(line.status());
        boolean paycorePaid = status.equals(PaymentStatus.SUCCEEDED.name());
        boolean paycoreFailed = status.equals(PaymentStatus.FAILED.name());

        ReconciliationResult result;
        String detail = null;

        if (railPaid && paycorePaid || !railPaid && paycoreFailed) {
            result = line.amountMinor() == payment.getAmountMinor() || !railPaid
                    ? ReconciliationResult.MATCHED
                    : ReconciliationResult.AMOUNT_MISMATCH;
            if (result == ReconciliationResult.AMOUNT_MISMATCH) {
                detail = "The rail paid a different amount from what the customer was debited.";
            }
        } else {
            result = ReconciliationResult.STATUS_MISMATCH;
            detail = railPaid && paycoreFailed
                    ? "The rail paid the beneficiary, but PayCore returned the customer's money: PayCore paid twice."
                    : !railPaid && paycorePaid
                            ? "The rail reports this transfer failed or was returned (" + line.reason() + "), but the "
                                    + "customer is still debited. Return the money: request a reversal of the debit."
                            : "PayCore is still waiting for this transfer's outcome. Check the payment's status.";
        }

        tally.add(new Draft(
                ItemKind.OUTBOUND, line.sessionId(), result, payment.getCurrency(),
                new Side(line.amountMinor(), line.status()), new Side(payment.getAmountMinor(), status),
                linksOf(payment), detail
        ));
    }

    private void inbound(Tally tally, RailSettlementLine line, Optional<LedgerTransaction> found) {

        boolean railPaid = RailSettlementLine.SUCCESSFUL.equals(line.status());

        if (found.isEmpty()) {

            UUID accountId = accountRepository.findByAccountNumber(line.accountNumber()).map(Account::getId).orElse(null);

            tally.add(new Draft(
                    ItemKind.INBOUND, line.sessionId(),
                    railPaid ? ReconciliationResult.MISSING_LOCAL : ReconciliationResult.MATCHED,
                    line.currency(), new Side(line.amountMinor(), line.status()), Side.MISSING,
                    new Links(null, null, accountId),
                    railPaid
                            ? "Money arrived in PayCore's settlement bank account but no customer was credited: the "
                                    + "rail's notification never reached PayCore. Ask the rail to deliver it again."
                            : null
            ));
            return;
        }

        LedgerTransaction credit = found.get();
        LedgerEntry entry = creditEntry(credit);
        String status = inboundStatus(credit);
        tally.paycoreRecords.add(credit.getId());
        countPaycore(tally, status, entry.getAmountMinor());

        ReconciliationResult result;
        String detail = null;

        if (!railPaid) {
            result = ReconciliationResult.STATUS_MISMATCH;
            detail = "The rail sent this money back to the sender, but PayCore credited the customer.";
        } else if (credit.getStatus() == LedgerTransactionStatus.REVERSED) {
            result = ReconciliationResult.STATUS_MISMATCH;
            detail = "The rail delivered this money, but PayCore reversed the customer's credit.";
        } else if (line.amountMinor() != entry.getAmountMinor()) {
            result = ReconciliationResult.AMOUNT_MISMATCH;
            detail = "The rail delivered a different amount from what the customer was credited.";
        } else {
            result = ReconciliationResult.MATCHED;
        }

        tally.add(new Draft(
                ItemKind.INBOUND, line.sessionId(), result, credit.getCurrency(),
                new Side(line.amountMinor(), line.status()), new Side(entry.getAmountMinor(), status),
                new Links(null, credit.getId(), entry.getAccount().getId()), detail
        ));
    }

    private String outboundStatus(Payment payment) {

        if (payment.getStatus() == PaymentStatus.SUCCEEDED
                && transactionRepository.findById(payment.getLedgerTransactionId())
                        .map(transaction -> transaction.getStatus() == LedgerTransactionStatus.REVERSED)
                        .orElse(false)) {
            return PaymentStatus.FAILED.name();
        }

        return payment.getStatus().name();
    }

    private static String inboundStatus(LedgerTransaction credit) {
        return credit.getStatus() == LedgerTransactionStatus.REVERSED ? "REVERSED" : "CREDITED";
    }

    private LedgerEntry creditEntry(LedgerTransaction transaction) {

        return entryRepository.findByTransactionIdOrderByIdAsc(transaction.getId()).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.CREDIT)
                .findFirst()
                .orElseThrow();
    }

    private Tally reconcileCard(FundingProvider processor, Instant from, Instant to) {

        String provider = processor.providerName();
        Tally tally = new Tally(provider);

        List<CardSettlementLine> report = processor.settlementReport(from.minus(LOOKUP_MARGIN), to.plus(LOOKUP_MARGIN));
        Map<String, CardSettlementLine> byReference = index(report, CardSettlementLine::merchantReference);

        for (CardSettlementLine line : report) {

            if (line.createdAt().isBefore(from) || !line.createdAt().isBefore(to)) {
                continue;
            }

            tally.providerLines++;

            if (CardPaymentRecord.CONFIRMED.equals(line.status())) {
                tally.providerTotal += line.amountMinor();
            }

            card(tally, line, paymentRepository.findByProviderAndExternalReference(provider, line.merchantReference()));
        }

        for (Payment payment : paymentRepository.findByTypeAndProviderAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                PaymentType.CARD_FUNDING, provider, from, to)) {

            if (tally.seen(payment.getId())) {
                continue;
            }

            CardSettlementLine line = byReference.get(payment.getExternalReference());

            if (line != null) {
                card(tally, line, Optional.of(payment));
                continue;
            }

            tally.paycoreRecords.add(payment.getId());

            if (payment.getStatus() == PaymentStatus.FAILED) {
                continue;
            }

            countPaycore(tally, payment.getStatus().name(), payment.getAmountMinor());

            tally.add(new Draft(
                    ItemKind.CARD, payment.getExternalReference(), ReconciliationResult.MISSING_PROVIDER,
                    payment.getCurrency(), Side.MISSING, new Side(payment.getAmountMinor(), payment.getStatus().name()),
                    linksOf(payment),
                    payment.getStatus() == PaymentStatus.SUCCEEDED
                            ? "PayCore credited a top-up the processor has not settled to PayCore's bank. Chase the "
                                    + "processor for the money, or recover it from the customer."
                            : "PayCore is still waiting for this top-up and the processor has not settled it."
            ));
        }

        return tally;
    }

    private void card(Tally tally, CardSettlementLine line, Optional<Payment> found) {

        if (found.isEmpty()) {
            tally.add(new Draft(
                    ItemKind.CARD, line.merchantReference(), ReconciliationResult.MISSING_LOCAL, line.currency(),
                    new Side(line.amountMinor(), line.status()), Side.MISSING, Links.NONE,
                    "The processor settled a charge PayCore has no top-up for. Find the customer and credit or refund it."
            ));
            return;
        }

        Payment payment = found.get();
        String status = payment.getStatus().name();
        tally.paycoreRecords.add(payment.getId());
        countPaycore(tally, status, payment.getAmountMinor());

        ReconciliationResult result = ReconciliationResult.MATCHED;
        String detail = null;

        switch (line.status()) {

            case CardPaymentRecord.CONFIRMED -> {
                if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                    if (line.amountMinor() != payment.getAmountMinor()) {
                        result = ReconciliationResult.AMOUNT_MISMATCH;
                        detail = "The processor charged the card a different amount from what the customer was "
                                + "credited. Adjust the customer's balance or refund the difference.";
                    }
                } else {
                    result = ReconciliationResult.STATUS_MISMATCH;
                    detail = payment.getStatus() == PaymentStatus.FAILED
                            ? "The card was charged, but the customer was neither credited nor refunded."
                            : "The processor settled this charge while PayCore is still waiting for it. Check the "
                                    + "payment's status.";
                }
            }

            case CardPaymentRecord.DECLINED -> {
                if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                    result = ReconciliationResult.STATUS_MISMATCH;
                    detail = "The processor declined this charge, but PayCore credited the customer.";
                }
            }

            case CardPaymentRecord.REFUNDED -> {
                if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                    result = ReconciliationResult.STATUS_MISMATCH;
                    detail = "The charge was refunded to the card, but the customer was also credited.";
                } else if (payment.getRefundReference() == null) {
                    result = ReconciliationResult.STATUS_MISMATCH;
                    detail = "The processor refunded this charge, but PayCore has no record of the refund.";
                }
            }

            default -> {
                result = ReconciliationResult.STATUS_MISMATCH;
                detail = "The processor reports an unknown status " + line.status() + ".";
            }
        }

        tally.add(new Draft(
                ItemKind.CARD, line.merchantReference(), result, payment.getCurrency(),
                new Side(line.amountMinor(), line.status()), new Side(payment.getAmountMinor(), status),
                linksOf(payment), detail
        ));
    }

    @Transactional
    public ReconciliationItem redeliver(UUID itemId, UUID officerId) {

        ReconciliationItem item = lockItem(itemId);

        if (item.getKind() != ItemKind.INBOUND
                || item.getResult() != ReconciliationResult.MISSING_LOCAL
                || item.getResolutionStatus() != ResolutionStatus.OPEN) {
            throw new IllegalArgumentException("Only an open missing inbound transfer can be delivered again");
        }

        InboundTransfer inbound =
                requireRail()
                        .redeliverInbound(item.getExternalReference())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "The rail has no inbound session " + item.getExternalReference() + " to deliver"
                        ));

        TransactionResponse credit = ledgerService.receiveInboundTransfer(inbound);

        item.resolve(officerId, "Re-delivered by the rail; credited as " + credit.reference());

        log.info("Inbound session {} re-delivered by the rail and credited as {}", item.getExternalReference(), credit.reference());

        return item;
    }

    @Transactional
    public ReconciliationItem resolve(UUID itemId, UUID supervisorId, String note) {

        ReconciliationItem item = lockItem(itemId);
        item.resolve(supervisorId, note);
        return item;
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> recentRuns(int limit) {
        return runRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public ReconciliationRun getRun(UUID runId) {
        return runRepository.findById(runId).orElseThrow(ReconciliationRunNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationItem> items(UUID runId) {
        getRun(runId);
        return itemRepository.findByRun(runId);
    }

    private ReconciliationItem lockItem(UUID itemId) {
        return itemRepository.findByIdForUpdate(itemId).orElseThrow(ReconciliationItemNotFoundException::new);
    }

    private BankRail requireRail() {

        BankRail rail = rails.getIfAvailable();

        if (rail == null) {
            throw new BankTransfersDisabledException();
        }

        return rail;
    }

    private FundingProvider requireFundingProvider() {

        FundingProvider provider = fundingProviders.getIfAvailable();

        if (provider == null) {
            throw new FundingDisabledException();
        }

        return provider;
    }

    private static Links linksOf(Payment payment) {
        return new Links(payment.getId(), payment.getLedgerTransactionId(), payment.getAccountId());
    }

    private static void countPaycore(Tally tally, String status, long amountMinor) {

        if (status.equals(PaymentStatus.SUCCEEDED.name()) || status.equals("CREDITED")) {
            tally.paycoreTotal += amountMinor;
        }
    }

    private static <T> Map<String, T> index(List<T> lines, Function<T, String> key) {
        return lines.stream().collect(Collectors.toMap(key, line -> line, (first, second) -> first, LinkedHashMap::new));
    }

    private record Draft(
            ItemKind kind,
            String reference,
            ReconciliationResult result,
            String currency,
            Side provider,
            Side paycore,
            Links links,
            String detail
    ) {
    }

    private static final class Tally {

        final String provider;
        final List<Draft> items = new ArrayList<>();
        final Set<UUID> paycoreRecords = new HashSet<>();
        final Set<String> references = new HashSet<>();
        int providerLines;
        long providerTotal;
        long paycoreTotal;

        Tally(String provider) {
            this.provider = provider;
        }

        boolean seen(UUID paycoreRecord) {
            return paycoreRecords.contains(paycoreRecord);
        }

        void add(Draft draft) {
            if (references.add(draft.kind() + ":" + draft.reference())) {
                items.add(draft);
            }
        }
    }
}
