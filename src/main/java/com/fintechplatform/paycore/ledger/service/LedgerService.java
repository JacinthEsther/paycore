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

/**
 * Moves money through the double-entry ledger.
 *
 * Every operation is two entries of the same amount, a debit and a credit.
 * Customer money only ever moves between two customer accounts or between
 * a customer account and PayCore's settlement account, the real money
 * PayCore holds at its bank:
 *
 * <pre>
 * transfer           DEBIT sender customer   CREDIT recipient customer
 * card top-up        DEBIT settlement        CREDIT customer
 * inbound transfer   DEBIT settlement        CREDIT customer
 * outbound transfer  DEBIT customer          CREDIT settlement
 * adjustment         either way against settlement (maker-checker)
 * reversal           the original with the sides swapped
 * </pre>
 *
 * Nobody can put money into an account by typing an amount: it arrives
 * because a provider or another bank says it did, and staff corrections
 * need two officers.
 *
 * PostgreSQL is the consistency boundary: an operation is one database
 * transaction that locks the customer accounts involved, checks balances
 * under that lock, and inserts the transaction and its entries. Either all
 * of it commits or none of it does.
 */
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
    private final SecureRandom random = new SecureRandom();

    public LedgerService(
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            AccountRepository accountRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
    }

    // ============================================================
    // CUSTOMER TRANSFERS INSIDE PAYCORE
    // ============================================================

    /**
     * Transfers from one of the customer's accounts to any active PayCore
     * customer account in the same currency.
     *
     * Retrying with the same idempotency key and identical details returns
     * the original transaction and moves no money; the same key with
     * different details is refused.
     */
    @Transactional
    public TransactionResponse transfer(
            UUID customerId,
            UUID sourceAccountId,
            TransferRequest request
    ) {

        Money amount = parseAmount(request.amount(), request.currency());
        String idempotencyKey = request.idempotencyKey().trim();
        String description = normalize(request.description());

        // The id only: the account itself is first read under its lock.
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

        // Fast path for a retry of a transfer that already went through.
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

        // Checked again under the lock: a concurrent duplicate of this
        // request that locked the source first has committed by now, and
        // this one must return its result rather than move money again.
        replay = replay(posting);

        if (replay.isPresent()) {
            return toResponse(replay.get());
        }

        requireCanSend(source, amount.currency());
        requireCanReceive(destination, amount.currency());
        requireFunds(source, amount);

        return toResponse(record(posting, source, destination, null));
    }

    // ============================================================
    // CARD TOP-UPS THROUGH A PAYMENT PROVIDER
    // ============================================================
    //
    // The caller charges the provider between the two steps, outside any
    // database transaction, so no row lock is held while the provider
    // answers:
    //
    //   checkFunding   repeat of an earlier top-up? else may this one go ahead?
    //   (provider)     takes the payment, or declines it
    //   postFunding    credits the account, checking everything again

    /**
     * The top-up this key already paid for, if this is an exact repeat;
     * otherwise empty once the top-up is allowed: the account is the
     * customer's own, active, in the currency, and within its limit.
     * Nothing is locked; postFunding checks it all again under the lock.
     */
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

    /**
     * Credits the payment the provider confirmed. A retry of a top-up that
     * already posted returns it; the same provider payment is never
     * credited twice (the database refuses a second one).
     */
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

        // The lock also serializes top-ups to the account, so two at once
        // cannot both fit under the limit.
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

    /**
     * How much more the customer can add to their own account by card.
     * provider is null when no card provider is configured.
     */
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

    /**
     * Active, in the currency, and with room under the card limit. On the
     * locked account, the funded total cannot change before commit.
     */
    private void requireCanFund(Account account, Money amount, FundingTerms terms) {

        requireOwnActive(account, "receive");
        requireCurrency(account, amount.currency());

        Money limit = Money.ofMajor(terms.limitPerAccount(), amount.currency());
        Money funded = Money.ofMinor(entryRepository.calculateProviderFunded(account.getId()), amount.currency());

        if (funded.add(amount).isGreaterThan(limit)) {
            throw new FundingLimitExceededException(limit, remaining(limit, funded));
        }
    }

    /** Zero, not negative, if the limit was lowered below what was funded. */
    private static Money remaining(Money limit, Money funded) {
        return funded.isGreaterThanOrEqualTo(limit) ? Money.ofMinor(0, limit.currency()) : limit.subtract(funded);
    }

    // ============================================================
    // TRANSFERS IN FROM OTHER BANKS
    // ============================================================

    /**
     * Credits a transfer another bank sent, as the bank rail reported it
     * (DEBIT settlement, CREDIT customer). The rail's session id makes it
     * idempotent: a notification delivered twice is credited once and the
     * second delivery gets the same transaction back.
     *
     * @throws InboundTransferRejectedException when the account cannot
     *         take the money (unknown, not active, wrong currency), so the
     *         rail returns it to the sending bank
     */
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

        // A concurrent delivery of the same session committed while this
        // one waited for the lock.
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

    /**
     * Total minor units credited to the account by inbound transfers from
     * the given provider that still stand. For a simulator's own limit.
     */
    @Transactional(readOnly = true)
    public long inboundTotal(UUID accountId, String provider) {
        return entryRepository.calculateInboundReceived(accountId, provider);
    }

    /** The same session reported again must carry the same transfer. */
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

    // ============================================================
    // TRANSFERS OUT TO OTHER BANKS
    // ============================================================
    //
    // Like any bank: the customer is debited first, then the rail is asked
    // to pay the other bank. If the rail rejects the payment, the debit is
    // reversed automatically and the customer has their money back.
    //
    //   findOutbound          a retry of a transfer already sent? return it
    //   postOutbound          DEBIT customer, CREDIT settlement, under the lock
    //   (rail)                pays the beneficiary bank, or rejects
    //   reverseRejectedOutbound   only on rejection: the money goes back

    /**
     * The outbound transfer this customer already made with the key, if
     * any. The details are compared again when it is posted.
     */
    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findOutbound(UUID customerId, String idempotencyKey) {

        return transactionRepository
                .findByInitiatedByAndIdempotencyKey(customerId, idempotencyKey.trim())
                .filter(found -> found.getType() == LedgerTransactionType.OUTBOUND_TRANSFER)
                .map(this::toResponse);
    }

    /**
     * Debits the customer for a transfer to another bank. The beneficiary
     * is the one name enquiry confirmed; sessionId is what the rail will
     * be asked to pay under.
     */
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

        Posting posting =
                new Posting(
                        LedgerTransactionType.OUTBOUND_TRANSFER,
                        amount,
                        customerId,
                        idempotencyKey,
                        description,
                        null,
                        sourceAccountId,
                        settlementIdOrNull(amount.currency()),
                        null,
                        outbound.provider(),
                        reference -> LedgerTransaction.createOutboundTransfer(
                                reference, amount.currency().code(), customerId, idempotencyKey, description,
                                outbound.provider(), outbound.sessionId(), outbound.beneficiary()
                        )
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

        Account settlement = settlementAccount(amount.currency());

        return toResponse(record(posting.withAccounts(source.getId(), settlement.getId()), source, settlement, null));
    }

    /**
     * Gives the customer their money back after the rail rejected an
     * outbound transfer: a REVERSAL with the sides swapped, made by the
     * rail rather than staff, so it works on any account the money left.
     * Reversing twice returns the first reversal.
     */
    @Transactional
    public TransactionResponse reverseRejectedOutbound(UUID transactionId, String reason) {

        LedgerTransaction original =
                transactionRepository
                        .findByIdForUpdate(transactionId)
                        .orElseThrow(LedgerTransactionNotFoundException::new);

        if (original.getType() != LedgerTransactionType.OUTBOUND_TRANSFER) {
            throw new IllegalArgumentException("Only an outbound transfer is reversed by the rail");
        }

        Optional<LedgerTransaction> existing = transactionRepository.findByReversesTransactionId(transactionId);

        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        List<LedgerEntry> entries = entryRepository.findByTransactionIdOrderByIdAsc(transactionId);
        Account customer = lockDestination(single(entries, LedgerEntryType.DEBIT).getAccount().getId());
        Account settlement = single(entries, LedgerEntryType.CREDIT).getAccount();
        Money amount = amountOf(original, entries);

        String description = "Reversal: transfer to " + original.getCounterparty().name() + " failed";

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

    // ============================================================
    // STAFF CORRECTIONS (MAKER-CHECKER)
    // ============================================================
    //
    // Staff never move money on their own. An operations officer requests
    // an adjustment or a reversal; a different officer approves it, and
    // only then is it posted, recording both. Neither may hold an account
    // the correction touches.

    /**
     * Whether an adjustment to the account could be requested at all, so
     * a maker cannot queue one that is bound to fail. Checked again in
     * full when it is approved.
     */
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

    /** Whether a reversal of the transaction could be requested. */
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

    /**
     * Posts an approved adjustment: CREDIT gives the customer money from
     * settlement, DEBIT takes it back. The idempotency key is the request
     * it came from, so approving twice posts once.
     */
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

    /**
     * Posts an approved reversal: the original's accounts and amount with
     * the sides swapped. The original's entries are never touched; the
     * original moves to REVERSED.
     *
     * Refused if the transaction is already reversed or is itself a
     * reversal, if it touches either officer's own account, if an account
     * involved is closed or still pending, or if the account being debited
     * no longer holds the money. Frozen accounts may take part: pulling a
     * fraudulent credit back out of a frozen account is what reversals are
     * for.
     */
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

        // Serializes reversals of the same transaction.
        LedgerTransaction original =
                transactionRepository
                        .findByIdForUpdate(transactionId)
                        .orElseThrow(LedgerTransactionNotFoundException::new);

        // Customer accounts in id order, as transfers lock them, so
        // reversals and transfers cannot deadlock. Settlement is not locked.
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

        // The swap: whoever was credited is now debited, and vice versa.
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

    /** Segregation of duties: nobody corrects their own money. */
    private static void requireNotOwnedBy(Account account, UUID officerId, String action) {

        if (account.isOwnedBy(officerId)) {
            throw new AccessDeniedException("Staff cannot " + action + " transactions on their own accounts");
        }
    }

    /** Active or frozen: closed and pending accounts hold no money to correct. */
    private static void requireCorrectable(Account account) {

        if (account.getStatus() == AccountStatus.CLOSED || account.getStatus() == AccountStatus.PENDING) {
            throw new InvalidAccountStateException(
                    "Account " + account.getAccountNumber() + " is "
                            + account.getStatus().name().toLowerCase()
                            + ", so it cannot be corrected"
            );
        }
    }

    // ============================================================
    // BALANCES AND READS
    // ============================================================

    /**
     * 404 unless the account is the customer's own.
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID customerId, UUID accountId) {

        return toBalance(
                accountRepository
                        .findByIdAndCustomerId(accountId, customerId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId))
        );
    }

    /**
     * Staff view of any customer account's balance.
     */
    @Transactional(readOnly = true)
    public BalanceResponse getCustomerAccountBalance(UUID accountId) {

        return toBalance(
                accountRepository
                        .findById(accountId)
                        .filter(account -> !account.isSystemAccount())
                        .orElseThrow(() -> new AccountNotFoundException(accountId))
        );
    }

    /**
     * PayCore's own accounts with their balances, for reconciliation. A
     * settlement account only appears once money has moved in its currency.
     */
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

    /**
     * 404 unless the transaction touches one of the customer's accounts,
     * as sender or recipient.
     */
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID customerId, UUID transactionId) {

        return transactionRepository
                .findById(transactionId)
                .filter(transaction -> involves(transaction, customerId))
                .map(this::toResponse)
                .orElseThrow(LedgerTransactionNotFoundException::new);
    }

    /**
     * Staff view of any transaction, including its staff note, e.g. to
     * find one to reverse.
     */
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

    // ============================================================
    // POSTING
    // ============================================================

    /**
     * What an operation asks for: its type, amount, who asked, their
     * idempotency key, the customer-facing description, the internal staff
     * note, the accounts to debit and credit, for a reversal the
     * transaction it undoes, and the provider that carried it. Compared
     * field by field when a key is reused (a provider's own reference is
     * not: a retry is recognised before the provider is asked again).
     *
     * create builds the transaction for the reference it is given.
     */
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

    /** A stored transaction with its entries, before it becomes a response. */
    private record Posted(LedgerTransaction transaction, List<LedgerEntry> entries) {
    }

    /**
     * Writes the transaction and its two entries, and for a reversal marks
     * the original REVERSED in the same database transaction. The accounts
     * have been validated and the customer ones locked by the caller.
     */
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

            // The same key raced in through a different lock (another
            // account). The database kept only one.
            if (ConstraintViolations.violates(exception, IDEMPOTENCY_CONSTRAINT)) {
                throw new IdempotencyConflictException();
            }

            // Unreachable while the original is locked; the database's
            // own guarantee that nothing is reversed twice.
            if (ConstraintViolations.violates(exception, REVERSAL_CONSTRAINT)) {
                throw new TransactionNotReversibleException("Transaction has already been reversed");
            }

            // The same provider payment reported twice; it was credited once.
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

    /**
     * The original transaction if this key was already used by the same
     * person, provided the request is an exact repeat; otherwise refused.
     */
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

    /**
     * Looked up, never created: a request in a currency no account uses
     * must not leave a settlement account behind.
     */
    private UUID settlementIdOrNull(Currency currency) {

        return accountRepository
                .findByTypeAndCurrencyAndCustomerIsNull(AccountType.SETTLEMENT, currency.code())
                .map(Account::getId)
                .orElse(null);
    }

    /**
     * The settlement account for the currency, created on first use. The
     * currency has already been checked against a customer account, so
     * only currencies PayCore supports ever get one.
     */
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

    // ============================================================
    // LOCKING AND CHECKS
    // ============================================================

    /**
     * Locks both accounts, always in the same (id) order whichever way the
     * money flows, so A -> B and B -> A running at once cannot deadlock.
     * The source is only found if it belongs to the customer.
     */
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

    /** For the customer's own account, whose status they may see. */
    private static void requireOwnActive(Account account, String action) {

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException(
                    "Your account is " + account.getStatus().name().toLowerCase()
                            + " and cannot " + action + " money"
            );
        }
    }

    /**
     * Does not say what state someone else's account is in.
     */
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

    /**
     * Every debit from a customer account needs that account's lock, which
     * the caller holds, so the balance cannot drop before commit.
     */
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

    // ============================================================
    // HELPERS
    // ============================================================

    /**
     * Balance in minor units on the account's normal side. Customer
     * accounts are what PayCore owes the customer: credits add, debits
     * subtract. The settlement account is money PayCore holds: debits add,
     * credits subtract. Either way a healthy balance is never negative.
     */
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

    /**
     * TXN-20260930-8F3A2C91D0B47E15: the UTC date for people, then 64
     * random bits. At a million transfers a day the chance of any same-day
     * collision is about 1 in 37 million per day; the unique constraint is
     * the backstop if it ever happens.
     */
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

    /**
     * Staff only: adds the internal staff note, who initiated the
     * transaction and who approved it.
     */
    private StaffTransactionResponse toStaffResponse(Posted posted) {

        return new StaffTransactionResponse(
                toResponse(posted),
                posted.transaction().getStaffNote(),
                posted.transaction().getInitiatedBy(),
                posted.transaction().getApprovedBy()
        );
    }

    /**
     * What customers see. Built only from customer-facing fields; the staff
     * note has no place in TransactionResponse.
     */
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

    /** The account holder's name, as a receipt shows it; "PayCore" for its own accounts. */
    static String accountName(Account account) {

        Customer customer = account.getCustomer();

        return customer == null ? "PayCore" : customer.getFirstName() + " " + customer.getLastName();
    }

    private record AccountPair(Account source, Account destination) {
    }
}
