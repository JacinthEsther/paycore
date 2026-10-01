package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.exception.AccountNotFoundException;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.kyc.dto.PageInfo;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.dto.response.StatementLineResponse;
import com.fintechplatform.paycore.ledger.dto.response.StatementResponse;
import com.fintechplatform.paycore.ledger.entity.LedgerEntry;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.repository.LedgerEntryRepository;
import com.fintechplatform.paycore.ledger.repository.StatementTotals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Account statements, derived from the ledger like every balance.
 *
 * A statement covers whole calendar days in PayCore's statement time zone
 * (Africa/Lagos by default), so a transfer at 23:30 UTC on 30 September
 * appears on 1 October, as the customer experienced it.
 *
 * Every figure comes from the database: the opening balance is everything
 * before the period, and each page's running balance starts from the
 * opening balance plus the period's earlier entries, so any page can be
 * read on its own.
 *
 * Statements are for customer accounts; system accounts are reconciled
 * through their balances instead.
 */
@Service
public class StatementService {

    static final int MAX_PERIOD_DAYS = 366;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final ZoneId timeZone;

    public StatementService(
            AccountRepository accountRepository,
            LedgerEntryRepository entryRepository,
            @Value("${paycore.ledger.statement-time-zone:Africa/Lagos}") ZoneId timeZone
    ) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.timeZone = timeZone;
    }

    /**
     * 404 unless the account is the customer's own.
     */
    @Transactional(readOnly = true)
    public StatementResponse getStatement(
            UUID customerId,
            UUID accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {

        Account account =
                accountRepository
                        .findByIdAndCustomerId(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        return statement(account, from, to, page, size);
    }

    /**
     * Staff view of any customer account's statement.
     */
    @Transactional(readOnly = true)
    public StatementResponse getCustomerAccountStatement(
            UUID accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {

        Account account =
                accountRepository
                        .findById(accountId)
                        .filter(found -> !found.isSystemAccount())
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        return statement(account, from, to, page, size);
    }

    /**
     * Without dates, the month to date. With only a start date, from then
     * to today.
     */
    private StatementResponse statement(
            Account account,
            LocalDate requestedFrom,
            LocalDate requestedTo,
            int page,
            int size
    ) {

        LocalDate to = requestedTo != null ? requestedTo : LocalDate.now(timeZone);
        LocalDate from = requestedFrom != null ? requestedFrom : to.withDayOfMonth(1);

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }

        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException(
                    "A statement can cover at most " + MAX_PERIOD_DAYS + " days"
            );
        }

        // [start, end): from's first instant up to the day after to.
        Instant start = from.atStartOfDay(timeZone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(timeZone).toInstant();

        UUID accountId = account.getId();
        int minorUnit = Currency.of(account.getCurrency()).minorUnit();

        long opening = entryRepository.calculateBalanceBefore(accountId, start);
        StatementTotals totals = entryRepository.calculateStatementTotals(accountId, start, end);

        List<LedgerEntry> entries =
                entryRepository.findStatementLines(accountId, start, end, PageRequest.of(page, size));

        // Running balance at the start of this page.
        long running =
                entries.isEmpty() || page == 0
                        ? opening
                        : opening + entryRepository.calculatePeriodBalanceBefore(
                                accountId,
                                start,
                                entries.getFirst().getCreatedAt(),
                                entries.getFirst().getId()
                        );

        // The other side of every line on the page, in one query.
        Map<UUID, List<LedgerEntry>> siblings =
                entries.isEmpty()
                        ? Map.of()
                        : entryRepository
                                .findByTransactionIdIn(entries.stream().map(e -> e.getTransaction().getId()).toList())
                                .stream()
                                .collect(Collectors.groupingBy(e -> e.getTransaction().getId()));

        List<StatementLineResponse> lines = new ArrayList<>(entries.size());

        for (LedgerEntry entry : entries) {

            boolean credit = entry.getEntryType() == LedgerEntryType.CREDIT;
            running += credit ? entry.getAmountMinor() : -entry.getAmountMinor();

            Counterparty other = counterparty(entry, siblings.getOrDefault(entry.getTransaction().getId(), List.of()));

            lines.add(new StatementLineResponse(
                    entry.getCreatedAt(),
                    entry.getTransaction().getId(),
                    entry.getTransaction().getReference(),
                    entry.getTransaction().getType(),
                    entry.getTransaction().getStatus(),
                    entry.getTransaction().getDescription(),
                    other == null ? null : other.name(),
                    other == null ? null : other.bank(),
                    entry.getEntryType(),
                    BigDecimal.valueOf(entry.getAmountMinor(), minorUnit),
                    BigDecimal.valueOf(running, minorUnit)
            ));
        }

        int totalPages = (int) ((totals.lines() + size - 1) / size);
        long closing = opening + totals.creditsMinor() - totals.debitsMinor();

        return new StatementResponse(
                accountId,
                account.getAccountNumber(),
                account.getCurrency(),
                from,
                to,
                timeZone.getId(),
                BigDecimal.valueOf(opening, minorUnit),
                BigDecimal.valueOf(totals.creditsMinor(), minorUnit),
                BigDecimal.valueOf(totals.debitsMinor(), minorUnit),
                BigDecimal.valueOf(closing, minorUnit),
                lines,
                new PageInfo(page, size, totals.lines(), totalPages, page + 1 < totalPages)
        );
    }

    /**
     * Who is on the other side of the line, as a banking app names them:
     * the other bank's account for a transfer in or out, the other
     * customer for a transfer inside PayCore, nobody for money PayCore
     * itself moved (top-ups, adjustments).
     */
    private static Counterparty counterparty(LedgerEntry entry, List<LedgerEntry> transactionEntries) {

        Counterparty external = entry.getTransaction().getCounterparty();

        if (external != null) {
            return external;
        }

        return transactionEntries.stream()
                .filter(other -> !other.getId().equals(entry.getId()))
                .map(LedgerEntry::getAccount)
                .filter(account -> !account.isSystemAccount())
                .findFirst()
                .map(account -> new Counterparty(LedgerService.accountName(account), "PayCore", account.getAccountNumber()))
                .orElse(null);
    }
}
