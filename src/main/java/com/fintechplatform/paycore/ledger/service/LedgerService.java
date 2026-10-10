package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.account.exception.AccountNotFoundException;
import com.fintechplatform.paycore.account.exception.InvalidAccountStateException;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.common.persistence.ConstraintViolations;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.dto.request.TransferRequest;
import com.fintechplatform.paycore.ledger.dto.response.BalanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.FundingAllowanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.LedgerEntryResponse;
import com.fintechplatform.paycore.ledger.dto.response.StaffTransactionResponse;
import com.fintechplatform.paycore.ledger.dto.response.SystemAccountResponse;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.entity.LedgerEntry;
import com.fintechplatform.paycore.ledger.entity.LedgerTransaction;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import com.fintechplatform.paycore.ledger.exception.CurrencyMismatchException;
import com.fintechplatform.paycore.ledger.exception.FundingLimitExceededException;
import com.fintechplatform.paycore.ledger.exception.IdempotencyConflictException;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import com.fintechplatform.paycore.ledger.exception.InsufficientFundsException;
import com.fintechplatform.paycore.ledger.exception.InvalidLedgerTransactionException;
import com.fintechplatform.paycore.ledger.exception.LedgerTransactionNotFoundException;
import com.fintechplatform.paycore.ledger.exception.TransactionNotReversibleException;
import com.fintechplatform.paycore.ledger.repository.LedgerEntryRepository;
import com.fintechplatform.paycore.ledger.repository.LedgerTransactionRepository;
import com.fintechplatform.paycore.limits.exception.BalanceLimitExceededException;
import com.fintechplatform.paycore.limits.service.SpendingLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class LedgerService {

    static final String IDEMPOTENCY_CONSTRAINT = "uk_ledger_transactions_initiator_idempotency_key";
    static final String REVERSAL_CONSTRAINT = "uk_ledger_transactions_reverses";
    static final String PROVIDER_PAYMENT_CONSTRAINT = "uk_ledger_transactions_provider_payment";

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private static final DateTimeFormatter REFERENCE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountRepository accountRepository;
    private final SpendingLimitService spendingLimits;
    private final SecureRandom random = new SecureRandom();

    public LedgerService(
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            AccountRepository accountRepository,
            SpendingLimitService spendingLimits
    ) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.spendingLimits = spendingLimits;
    }

    @Transactional
    public TransactionResponse transfer(
            UUID customerId,
            UUID sourceAccountId,
            TransferRequest request
    ) {

        Money amount = parseAmount(request.amount(), request.currency());
        String idempotencyKey = request.idempotencyKey().trim();
        String description = normalize(request.description());

        UUID destinationAccountId =
                accountRepository
                        .findIdByAccountNumber(request.destinationAccountNumber())
                        .orElseThrow(() ->
                                AccountNotFoundException.withAccountNumber(request.destinationAccountNumber())
                        );

        Posting posting =
                new Posting(
                        LedgerTransactionType.TRANSFER,
                        amount,
                        customerId,
                        idempotencyKey,
                        description,
                        null,
                        sourceAccountId,
                        destinationAccountId,
                        null,
                        null,
                        reference -> LedgerTransaction.createTransfer(
                                reference, amount.currency().code(), customerId, idempotencyKey, description
                        )
                );

        Optional<Posted> replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        if (sourceAccountId.equals(destinationAccountId)) {
            throw new InvalidLedgerTransactionException(
                    "Source and destination accounts must be different"
            );
        }

        AccountPair accounts = lockAccounts(customerId, sourceAccountId, destinationAccountId);
        Account source = accounts.source();
        Account destination = accounts.destination();

        replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        requireCanSend(source, amount.currency());
        requireCanReceive(destination, amount.currency());
        requireFunds(source, amount);
        spendingLimits.requireWithinLimits(source, amount);
        spendingLimits.requireCanHold(destination, balanceOf(destination), amount, false);

        return toResponse(record(posting, source, destination, null));
    }

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> checkFunding(
            UUID customerId,
            UUID accountId,
            FundAccountRequest request,
            FundingTerms terms
    ) {

        Money amount = parseAmount(request.amount(), request.currency());
        Posting posting = fundingPosting(customerId, accountId, request, terms, amount, null);

        Optional<Posted> replay = replay(posting);

        if (replay.isPresent()) {
            return replay.map(this::toResponse);
        }

        Account account =
                accountRepository
                        .findByIdAndCustomerId(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        requireCanFund(account, amount, terms);

        return Optional.empty();
    }

    @Transactional
    public TransactionResponse postFunding(
            UUID customerId,
            UUID accountId,
            FundAccountRequest request,
            FundingTerms terms,
            String providerReference
    ) {

        Money amount = parseAmount(request.amount(), request.currency());
        Posting posting = fundingPosting(customerId, accountId, request, terms, amount, providerReference);

        Optional<Posted> replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        Account account =
                accountRepository
                        .findByIdAndCustomerIdForUpdate(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        requireCanFund(account, amount, terms);

        Account settlement = settlementAccount(amount.currency());

        return toResponse(
                record(posting.withAccounts(settlement.getId(), account.getId()), settlement, account, null)
        );
    }

    @Transactional(readOnly = true)
    public FundingAllowanceResponse getFundingAllowance(
            UUID customerId,
            UUID accountId,
            String provider,
            BigDecimal limitPerAccount
    ) {

        Account account =
                accountRepository
                        .findByIdAndCustomerId(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        Currency currency = Currency.of(account.getCurrency());
        Money limit = Money.ofMajor(limitPerAccount, currency);
        Money funded = Money.ofMinor(entryRepository.calculateProviderFunded(account.getId()), currency);

        return new FundingAllowanceResponse(
                account.getId(),
                provider != null,
                provider,
                currency.code(),
                limit.toMajor(),
                funded.toMajor(),
                remaining(limit, funded).toMajor()
        );
    }

    private Posting fundingPosting(
            UUID customerId,
            UUID accountId,
            FundAccountRequest request,
            FundingTerms terms,
            Money amount,
            String providerReference
    ) {

        String idempotencyKey = request.idempotencyKey().trim();

        return new Posting(
                LedgerTransactionType.DEPOSIT,
                amount,
                customerId,
                idempotencyKey,
                terms.description(),
                null,
                settlementIdOrNull(amount.currency()),
                accountId,
                null,
                terms.provider(),
                reference -> LedgerTransaction.createProviderDeposit(
                        reference, amount.currency().code(), customerId, idempotencyKey,
                        terms.description(), terms.provider(), providerReference
                )
        );
    }

    private void requireCanFund(Account account, Money amount, FundingTerms terms) {

        requireOwnActive(account, "receive");
        requireCurrency(account, amount.currency());

        Money limit = Money.ofMajor(terms.limitPerAccount(), amount.currency());
        Money funded = Money.ofMinor(entryRepository.calculateProviderFunded(account.getId()), amount.currency());

        if (funded.add(amount).isGreaterThan(limit)) {
            throw new FundingLimitExceededException(limit, remaining(limit, funded));
        }

        spendingLimits.requireCanHold(account, balanceOf(account), amount, true);
    }

    private static Money remaining(Money limit, Money funded) {
        return funded.isGreaterThanOrEqualTo(limit) ? Money.ofMinor(0, limit.currency()) : limit.subtract(funded);
    }

    @Transactional
    public TransactionResponse receiveInboundTransfer(InboundTransfer inbound) {

        Money amount = parseAmount(inbound.amount(), inbound.currency());

        Optional<LedgerTransaction> previous =
                transactionRepository.findByProviderAndProviderReference(inbound.provider(), inbound.sessionId());

        if (previous.isPresent()) {
            return toResponse(sameInbound(previous.get(), inbound, amount));
        }

        UUID accountId =
                accountRepository
                        .findIdByAccountNumber(inbound.destinationAccountNumber())
                        .orElseThrow(() -> new InboundTransferRejectedException("Account number not found"));

        Account account =
                accountRepository
                        .findByIdForUpdate(accountId)
                        .filter(found -> !found.isSystemAccount())
                        .orElseThrow(() -> new InboundTransferRejectedException("Account number not found"));

        previous = transactionRepository.findByProviderAndProviderReference(inbound.provider(), inbound.sessionId());

        if (previous.isPresent()) {
            return toResponse(sameInbound(previous.get(), inbound, amount));
        }

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InboundTransferRejectedException("Account cannot receive money");
        }

        if (!account.getCurrency().equals(amount.currency().code())) {
            throw new InboundTransferRejectedException("Account does not hold " + amount.currency().code());
        }

        try {
            spendingLimits.requireCanHold(account, balanceOf(account), amount, false);
        } catch (BalanceLimitExceededException exceeded) {
            throw new InboundTransferRejectedException("Account balance limit exceeded");
        }

        String description =
                Objects.requireNonNullElse(normalize(inbound.narration()), "Transfer from " + inbound.sender().name());

        Account settlement = settlementAccount(amount.currency());

        Posting posting =
                new Posting(
                        LedgerTransactionType.INBOUND_TRANSFER,
                        amount,
                        null,
                        inbound.sessionId(),
                        description,
                        null,
                        settlement.getId(),
                        account.getId(),
                        null,
                        inbound.provider(),
                        reference -> LedgerTransaction.createInboundTransfer(
                                reference, amount.currency().code(), description,
                                inbound.provider(), inbound.sessionId(), inbound.sender()
                        )
                );

        return toResponse(record(posting, settlement, account, null));
    }

    @Transactional(readOnly = true)
    public long inboundTotal(UUID accountId, String provider) {
        return entryRepository.calculateInboundReceived(accountId, provider);
    }

    private Posted sameInbound(LedgerTransaction previous, InboundTransfer inbound, Money amount) {

        Posted posted = posted(previous);
        LedgerEntry credit = single(posted.entries(), LedgerEntryType.CREDIT);

        boolean same =
                previous.getType() == LedgerTransactionType.INBOUND_TRANSFER
                        && previous.getCurrency().equals(amount.currency().code())
                        && credit.getAmountMinor() == amount.amountMinor()
                        && Objects.equals(credit.getAccount().getAccountNumber(), inbound.destinationAccountNumber());

        if (!same) {
            log.error(
                    "{} reported session {} again with different details; the first report stands",
                    inbound.provider(), inbound.sessionId()
            );
            throw new IdempotencyConflictException();
        }

        return posted;
    }

    @Transactional
    public TransactionResponse postOutbound(
            UUID customerId,
            UUID sourceAccountId,
            OutboundTransfer outbound
    ) {

        Money amount = parseAmount(outbound.amount(), outbound.currency());
        String idempotencyKey = outbound.idempotencyKey().trim();
        String description =
                Objects.requireNonNullElse(normalize(outbound.narration()), "Transfer to " + outbound.beneficiary().name());

        return postProviderDebit(
                LedgerTransactionType.OUTBOUND_TRANSFER,
                customerId,
                sourceAccountId,
                amount,
                idempotencyKey,
                description,
                outbound.provider(),
                reference -> LedgerTransaction.createOutboundTransfer(
                        reference, amount.currency().code(), customerId, idempotencyKey, description,
                        outbound.provider(), outbound.sessionId(), outbound.beneficiary()
                )
        );
    }

    @Transactional
    public TransactionResponse postBillPayment(UUID customerId, UUID sourceAccountId, BillDebit bill) {

        Money amount = parseAmount(bill.amount(), bill.currency());
        String idempotencyKey = bill.idempotencyKey().trim();

        return postProviderDebit(
                LedgerTransactionType.BILL_PAYMENT,
                customerId,
                sourceAccountId,
                amount,
                idempotencyKey,
                bill.description(),
                bill.provider(),
                reference -> LedgerTransaction.createBillPayment(
                        reference, amount.currency().code(), customerId, idempotencyKey, bill.description(),
                        bill.provider(), bill.requestId(), bill.biller()
                )
        );
    }

    private TransactionResponse postProviderDebit(
            LedgerTransactionType type,
            UUID customerId,
            UUID sourceAccountId,
            Money amount,
            String idempotencyKey,
            String description,
            String provider,
            Function<String, LedgerTransaction> create
    ) {

        Posting posting =
                new Posting(
                        type,
                        amount,
                        customerId,
                        idempotencyKey,
                        description,
                        null,
                        sourceAccountId,
                        settlementIdOrNull(amount.currency()),
                        null,
                        provider,
                        create
                );

        Optional<Posted> replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        Account source = lockSource(customerId, sourceAccountId);

        replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        requireCanSend(source, amount.currency());
        requireFunds(source, amount);
        spendingLimits.requireWithinLimits(source, amount);

        Account settlement = settlementAccount(amount.currency());

        return toResponse(record(posting.withAccounts(source.getId(), settlement.getId()), source, settlement, null));
    }

    @Transactional
    public TransactionResponse reverseRejectedOutbound(UUID transactionId, String reason) {

        LedgerTransaction original =
                transactionRepository
                        .findByIdForUpdate(transactionId)
                        .orElseThrow(LedgerTransactionNotFoundException::new);

        if (original.getType() != LedgerTransactionType.OUTBOUND_TRANSFER
                && original.getType() != LedgerTransactionType.BILL_PAYMENT) {
            throw new IllegalArgumentException("Only a payment to a provider is reversed by the provider");
        }

        Optional<LedgerTransaction> existing = transactionRepository.findByReversesTransactionId(transactionId);

        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        List<LedgerEntry> entries = entryRepository.findByTransactionIdOrderByIdAsc(transactionId);
        Account customer = lockDestination(single(entries, LedgerEntryType.DEBIT).getAccount().getId());
        Account settlement = single(entries, LedgerEntryType.CREDIT).getAccount();
        Money amount = amountOf(original, entries);

        String description =
                original.getType() == LedgerTransactionType.BILL_PAYMENT
                        ? "Reversal: " + original.getCounterparty().name() + " payment failed"
                        : "Reversal: transfer to " + original.getCounterparty().name() + " failed";

        log.info("Reversing outbound transfer {}: {}", original.getReference(), reason);

        Posting posting =
                new Posting(
                        LedgerTransactionType.REVERSAL,
                        amount,
                        original.getInitiatedBy(),
                        "rail-reversal-" + transactionId,
                        description,
                        null,
                        settlement.getId(),
                        customer.getId(),
                        transactionId,
                        original.getProvider(),
                        reference -> LedgerTransaction.createRailReversal(
                                reference, original, "rail-reversal-" + transactionId, description,
                                "REV-" + original.getProviderReference()
                        )
                );

        return toResponse(record(posting, settlement, customer, original));
    }

    @Transactional(readOnly = true)
    public Account requireAdjustable(UUID officerId, UUID accountId, String currencyCode) {

        Account account =
                accountRepository
                        .findById(accountId)
                        .filter(found -> !found.isSystemAccount())
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        requireNotOwnedBy(account, officerId, "adjust");
        requireCorrectable(account);
        requireCurrency(account, Currency.of(currencyCode));

        return account;
    }

    @Transactional(readOnly = true)
    public LedgerTransaction requireReversible(UUID officerId, UUID transactionId) {

        LedgerTransaction transaction =
                transactionRepository
                        .findById(transactionId)
                        .orElseThrow(LedgerTransactionNotFoundException::new);

        requireReversibleState(transaction);

        for (UUID accountId : entryRepository.findCustomerAccountIds(transactionId)) {
            accountRepository.findById(accountId).ifPresent(account -> {
                requireNotOwnedBy(account, officerId, "reverse");
                requireCorrectable(account);
            });
        }

        return transaction;
    }

    @Transactional
    public StaffTransactionResponse postAdjustment(
            UUID requestedBy,
            UUID approvedBy,
            UUID accountId,
            LedgerEntryType direction,
            Money amount,
            String staffNote,
            String customerDescription,
            String idempotencyKey
    ) {

        if (!amount.isPositive()) {
            throw new InvalidLedgerTransactionException("Amount must be greater than zero");
        }

        boolean credit = direction == LedgerEntryType.CREDIT;
        String description = Objects.requireNonNullElse(normalize(customerDescription), "Account adjustment");
        UUID settlementId = settlementIdOrNull(amount.currency());

        Posting posting =
                new Posting(
                        LedgerTransactionType.ADJUSTMENT,
                        amount,
                        requestedBy,
                        idempotencyKey,
                        description,
                        staffNote,
                        credit ? settlementId : accountId,
                        credit ? accountId : settlementId,
                        null,
                        null,
                        reference -> LedgerTransaction.createAdjustment(
                                reference, amount.currency().code(), requestedBy, approvedBy,
                                idempotencyKey, description, staffNote
                        )
                );

        Optional<Posted> replay = replay(posting);

        if (replay.isPresent()) {
            return toStaffResponse(replay.get());
        }

        Account account =
                accountRepository
                        .findByIdForUpdate(accountId)
                        .filter(found -> !found.isSystemAccount())
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        requireNotOwnedBy(account, requestedBy, "adjust");
        requireNotOwnedBy(account, approvedBy, "adjust");
        requireCorrectable(account);
        requireCurrency(account, amount.currency());

        Account settlement = settlementAccount(amount.currency());

        if (credit) {
            return toStaffResponse(
                    record(posting.withAccounts(settlement.getId(), account.getId()), settlement, account, null)
            );
        }

        requireFunds(account, amount);

        return toStaffResponse(
                record(posting.withAccounts(account.getId(), settlement.getId()), account, settlement, null)
        );
    }

    @Transactional
    public StaffTransactionResponse reverse(
            UUID requestedBy,
            UUID approvedBy,
            UUID transactionId,
            String staffNote,
            String customerDescription,
            String idempotencyKey
    ) {

        Optional<LedgerTransaction> previous =
                transactionRepository.findByInitiatedByAndIdempotencyKey(requestedBy, idempotencyKey);

        if (previous.isPresent()) {
            return toStaffResponse(sameReversal(previous.get(), transactionId));
        }

        LedgerTransaction original =
                transactionRepository
                        .findByIdForUpdate(transactionId)
                        .orElseThrow(LedgerTransactionNotFoundException::new);

        List<Account> locked =
                entryRepository
                        .findCustomerAccountIds(transactionId)
                        .stream()
                        .distinct()
                        .sorted()
                        .map(this::lockDestination)
                        .toList();

        previous = transactionRepository.findByInitiatedByAndIdempotencyKey(requestedBy, idempotencyKey);

        if (previous.isPresent()) {
            return toStaffResponse(sameReversal(previous.get(), transactionId));
        }

        requireReversibleState(original);

        for (Account account : locked) {
            requireNotOwnedBy(account, requestedBy, "reverse");
            requireNotOwnedBy(account, approvedBy, "reverse");
            requireCorrectable(account);
        }

        List<LedgerEntry> entries = entryRepository.findByTransactionIdOrderByIdAsc(transactionId);
        LedgerEntry originalDebit = single(entries, LedgerEntryType.DEBIT);
        LedgerEntry originalCredit = single(entries, LedgerEntryType.CREDIT);

        Account debitAccount = originalCredit.getAccount();
        Account creditAccount = originalDebit.getAccount();
        Money amount = amountOf(original, entries);

        if (!debitAccount.isSystemAccount()) {
            requireFunds(debitAccount, amount);
        }

        String description =
                Objects.requireNonNullElse(normalize(customerDescription), "Reversal of " + original.getReference());

        Posting posting =
                new Posting(
                        LedgerTransactionType.REVERSAL,
                        amount,
                        requestedBy,
                        idempotencyKey,
                        description,
                        staffNote,
                        debitAccount.getId(),
                        creditAccount.getId(),
                        transactionId,
                        null,
                        reference -> LedgerTransaction.createReversal(
                                reference, original, requestedBy, approvedBy, idempotencyKey, description, staffNote
                        )
                );

        return toStaffResponse(record(posting, debitAccount, creditAccount, original));
    }

    private Posted sameReversal(LedgerTransaction previous, UUID transactionId) {

        if (previous.getType() != LedgerTransactionType.REVERSAL
                || !transactionId.equals(previous.getReversesTransactionId())) {
            throw new IdempotencyConflictException();
        }

        return posted(previous);
    }

    private static void requireReversibleState(LedgerTransaction transaction) {

        if (!transaction.isReversible()) {
            throw new TransactionNotReversibleException(
                    transaction.getType() == LedgerTransactionType.REVERSAL
                            ? "A reversal cannot itself be reversed; post a new transaction instead"
                            : "Transaction " + transaction.getReference() + " has already been reversed"
            );
        }
    }

    private static void requireNotOwnedBy(Account account, UUID officerId, String action) {

        if (account.isOwnedBy(officerId)) {
            throw new AccessDeniedException("Staff cannot " + action + " transactions on their own accounts");
        }
    }

    private static void requireCorrectable(Account account) {

        if (account.getStatus() == AccountStatus.CLOSED || account.getStatus() == AccountStatus.PENDING) {
            throw new InvalidAccountStateException(
                    "Account " + account.getAccountNumber() + " is "
                            + account.getStatus().name().toLowerCase()
                            + ", so it cannot be corrected"
            );
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID customerId, UUID accountId) {

        return toBalance(
                accountRepository
                        .findByIdAndCustomerId(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId))
        );
    }

    @Transactional(readOnly = true)
    public BalanceResponse getCustomerAccountBalance(UUID accountId) {

        return toBalance(
                accountRepository
                        .findById(accountId)
                        .filter(account -> !account.isSystemAccount())
                        .orElseThrow(() -> new AccountNotFoundException(accountId))
        );
    }

    @Transactional(readOnly = true)
    public List<SystemAccountResponse> getSystemAccounts() {

        return accountRepository
                .findByCustomerIsNullOrderByTypeAscCurrencyAsc()
                .stream()
                .map(account -> {
                    Currency currency = Currency.of(account.getCurrency());

                    return new SystemAccountResponse(
                            account.getId(),
                            account.getType(),
                            currency.code(),
                            BigDecimal.valueOf(balanceOf(account), currency.minorUnit())
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID customerId, UUID transactionId) {

        return transactionRepository
                .findById(transactionId)
                .filter(transaction -> involves(transaction, customerId))
                .map(this::toResponse)
                .orElseThrow(LedgerTransactionNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public StaffTransactionResponse getAnyTransaction(UUID transactionId) {

        return transactionRepository
                .findById(transactionId)
                .map(transaction -> toStaffResponse(posted(transaction)))
                .orElseThrow(LedgerTransactionNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public StaffTransactionResponse getAnyTransactionByReference(String reference) {

        return transactionRepository
                .findByReference(reference)
                .map(transaction -> toStaffResponse(posted(transaction)))
                .orElseThrow(LedgerTransactionNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionByReference(UUID customerId, String reference) {

        return transactionRepository
                .findByReference(reference)
                .filter(transaction -> involves(transaction, customerId))
                .map(this::toResponse)
                .orElseThrow(LedgerTransactionNotFoundException::new);
    }

    private record Posting(
            LedgerTransactionType type,
            Money amount,
            UUID initiatedBy,
            String idempotencyKey,
            String description,
            String staffNote,
            UUID debitAccountId,
            UUID creditAccountId,
            UUID reversesTransactionId,
            String provider,
            Function<String, LedgerTransaction> create
    ) {
        Posting withAccounts(UUID debitAccountId, UUID creditAccountId) {
            return new Posting(
                    type, amount, initiatedBy, idempotencyKey, description, staffNote,
                    debitAccountId, creditAccountId, reversesTransactionId, provider, create
            );
        }
    }

    private record Posted(LedgerTransaction transaction, List<LedgerEntry> entries) {
    }

    private Posted record(
            Posting posting,
            Account debitAccount,
            Account creditAccount,
            LedgerTransaction reversed
    ) {

        LedgerTransaction transaction = posting.create().apply(generateReference());

        List<LedgerEntry> entries =
                List.of(
                        LedgerEntry.debit(transaction, debitAccount, posting.amount()),
                        LedgerEntry.credit(transaction, creditAccount, posting.amount())
                );

        transaction.post(entries);

        if (reversed != null) {
            reversed.markReversed();
        }

        try {
            transactionRepository.save(transaction);
            entryRepository.saveAll(entries);
            entryRepository.flush();
        } catch (DataIntegrityViolationException exception) {

            if (ConstraintViolations.violates(exception, IDEMPOTENCY_CONSTRAINT)) {
                throw new IdempotencyConflictException();
            }

            if (ConstraintViolations.violates(exception, REVERSAL_CONSTRAINT)) {
                throw new TransactionNotReversibleException("Transaction has already been reversed");
            }

            if (ConstraintViolations.violates(exception, PROVIDER_PAYMENT_CONSTRAINT)) {
                throw new IdempotencyConflictException();
            }

            throw exception;
        }

        log.info(
                "{} {} of {} {}: debit {} credit {} by {}",
                posting.type(),
                transaction.getReference(),
                posting.amount().toMajor(),
                posting.amount().currency().code(),
                debitAccount.getId(),
                creditAccount.getId(),
                posting.initiatedBy() == null ? posting.provider() : posting.initiatedBy()
        );

        return new Posted(transaction, entries);
    }

    private Optional<Posted> replay(Posting posting) {

        Optional<LedgerTransaction> found =
                transactionRepository.findByInitiatedByAndIdempotencyKey(
                        posting.initiatedBy(),
                        posting.idempotencyKey()
                );

        if (found.isEmpty()) {
            return Optional.empty();
        }

        LedgerTransaction previous = found.get();
        List<LedgerEntry> entries = entryRepository.findByTransactionIdOrderByIdAsc(previous.getId());

        LedgerEntry debit = single(entries, LedgerEntryType.DEBIT);
        LedgerEntry credit = single(entries, LedgerEntryType.CREDIT);

        boolean sameRequest =
                previous.getType() == posting.type()
                        && previous.getCurrency().equals(posting.amount().currency().code())
                        && debit.getAmountMinor() == posting.amount().amountMinor()
                        && debit.getAccount().getId().equals(posting.debitAccountId())
                        && credit.getAccount().getId().equals(posting.creditAccountId())
                        && Objects.equals(previous.getDescription(), posting.description())
                        && Objects.equals(previous.getStaffNote(), posting.staffNote())
                        && Objects.equals(previous.getReversesTransactionId(), posting.reversesTransactionId())
                        && Objects.equals(previous.getProvider(), posting.provider());

        if (!sameRequest) {
            throw new IdempotencyConflictException();
        }

        return Optional.of(new Posted(previous, entries));
    }

    private UUID settlementIdOrNull(Currency currency) {

        return accountRepository
                .findByTypeAndCurrencyAndCustomerIsNull(AccountType.SETTLEMENT, currency.code())
                .map(Account::getId)
                .orElse(null);
    }

    private Account settlementAccount(Currency currency) {

        return accountRepository
                .findByTypeAndCurrencyAndCustomerIsNull(AccountType.SETTLEMENT, currency.code())
                .orElseGet(() -> {
                    accountRepository.createSystemAccountIfMissing(AccountType.SETTLEMENT.name(), currency.code());

                    return accountRepository
                            .findByTypeAndCurrencyAndCustomerIsNull(AccountType.SETTLEMENT, currency.code())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Settlement account for " + currency.code() + " could not be created"
                            ));
                });
    }

    private AccountPair lockAccounts(UUID customerId, UUID sourceAccountId, UUID destinationAccountId) {

        boolean sourceFirst = sourceAccountId.compareTo(destinationAccountId) < 0;

        Account source;
        Account destination;

        if (sourceFirst) {
            source = lockSource(customerId, sourceAccountId);
            destination = lockDestination(destinationAccountId);
        } else {
            destination = lockDestination(destinationAccountId);
            source = lockSource(customerId, sourceAccountId);
        }

        return new AccountPair(source, destination);
    }

    private Account lockSource(UUID customerId, UUID accountId) {

        return accountRepository
                .findByIdAndCustomerIdForUpdate(accountId, customerId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private Account lockDestination(UUID accountId) {

        return accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void requireCanSend(Account account, Currency currency) {
        requireOwnActive(account, "send");
        requireCurrency(account, currency);
    }

    private static void requireOwnActive(Account account, String action) {

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException(
                    "Your account is " + account.getStatus().name().toLowerCase()
                            + " and cannot " + action + " money"
            );
        }
    }

    private void requireCanReceive(Account account, Currency currency) {

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("The destination account cannot receive money");
        }

        requireCurrency(account, currency);
    }

    private void requireCurrency(Account account, Currency currency) {

        if (!account.getCurrency().equals(currency.code())) {
            throw new CurrencyMismatchException(account.getCurrency(), currency.code());
        }
    }

    private void requireFunds(Account account, Money amount) {

        if (balanceOf(account) < amount.amountMinor()) {
            throw new InsufficientFundsException();
        }
    }

    private Money parseAmount(BigDecimal value, String currencyCode) {

        Money amount = Money.ofMajor(value, Currency.of(currencyCode));

        if (!amount.isPositive()) {
            throw new InvalidLedgerTransactionException("Amount must be greater than zero");
        }

        return amount;
    }

    private long balanceOf(Account account) {

        long creditsMinusDebits = entryRepository.calculateBalance(account.getId(), account.getCurrency());

        return account.isSystemAccount() ? -creditsMinusDebits : creditsMinusDebits;
    }

    private BalanceResponse toBalance(Account account) {

        Currency currency = Currency.of(account.getCurrency());

        return new BalanceResponse(
                account.getId(),
                account.getAccountNumber(),
                BigDecimal.valueOf(balanceOf(account), currency.minorUnit()),
                currency.code()
        );
    }

    private static Money amountOf(LedgerTransaction transaction, List<LedgerEntry> entries) {

        return Money.ofMinor(
                single(entries, LedgerEntryType.DEBIT).getAmountMinor(),
                Currency.of(transaction.getCurrency())
        );
    }

    private static LedgerEntry single(List<LedgerEntry> entries, LedgerEntryType type) {

        List<LedgerEntry> matching =
                entries.stream()
                        .filter(entry -> entry.getEntryType() == type)
                        .toList();

        if (matching.size() != 1) {
            throw new IllegalStateException("Expected one " + type + " entry but found " + matching.size());
        }

        return matching.getFirst();
    }

    private boolean involves(LedgerTransaction transaction, UUID customerId) {
        return entryRepository.existsByTransactionIdAndAccountCustomerId(transaction.getId(), customerId);
    }

    private static String normalize(String description) {

        if (description == null || description.isBlank()) {
            return null;
        }

        return description.trim();
    }

    private String generateReference() {

        byte[] suffix = new byte[8];
        random.nextBytes(suffix);

        return "TXN-"
                + REFERENCE_DATE.format(Instant.now())
                + "-"
                + HexFormat.of().withUpperCase().formatHex(suffix);
    }

    private Posted posted(LedgerTransaction transaction) {

        return new Posted(
                transaction,
                entryRepository.findByTransactionIdOrderByIdAsc(transaction.getId())
        );
    }

    private TransactionResponse toResponse(LedgerTransaction transaction) {
        return toResponse(posted(transaction));
    }

    private StaffTransactionResponse toStaffResponse(Posted posted) {

        return new StaffTransactionResponse(
                toResponse(posted),
                posted.transaction().getStaffNote(),
                posted.transaction().getInitiatedBy(),
                posted.transaction().getApprovedBy()
        );
    }

    private TransactionResponse toResponse(Posted posted) {

        LedgerTransaction transaction = posted.transaction();
        int minorUnit = Currency.of(transaction.getCurrency()).minorUnit();

        List<LedgerEntryResponse> entryResponses =
                posted.entries().stream()
                        .map(entry -> new LedgerEntryResponse(
                                entry.getId(),
                                entry.getAccount().getId(),
                                entry.getAccount().getAccountNumber(),
                                accountName(entry.getAccount()),
                                entry.getEntryType(),
                                BigDecimal.valueOf(entry.getAmountMinor(), minorUnit),
                                entry.getCurrency(),
                                entry.getCreatedAt()
                        ))
                        .toList();

        return new TransactionResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getType(),
                transaction.getStatus(),
                BigDecimal.valueOf(single(posted.entries(), LedgerEntryType.DEBIT).getAmountMinor(), minorUnit),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getCounterparty(),
                transaction.getProvider(),
                transaction.getProviderReference(),
                transaction.getCreatedAt(),
                transaction.getPostedAt(),
                transaction.getReversedAt(),
                transaction.getReversesTransactionId(),
                entryResponses
        );
    }

    static String accountName(Account account) {

        Customer customer = account.getCustomer();

        return customer == null ? "PayCore" : customer.getFirstName() + " " + customer.getLastName();
    }

    private record AccountPair(Account source, Account destination) {
    }
}
