package com.fintechplatform.paycore.ledger.entity;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The double-entry rules: positive entries, one currency, debits equal
 * credits, and the INITIATED -> POSTED -> REVERSED lifecycle.
 */
class LedgerTransactionTest {

    private static final Currency NGN = Currency.of("NGN");

    private final Account esther = account("Esther", "1234567896");
    private final Account john = account("John", "1234567802");

    @Test
    void shouldStartInitiated() {

        LedgerTransaction transaction = transfer("NGN");

        assertThat(transaction.getStatus()).isEqualTo(LedgerTransactionStatus.INITIATED);
        assertThat(transaction.getCreatedAt()).isNotNull();
        assertThat(transaction.getPostedAt()).isNull();
        assertThat(transaction.getReversedAt()).isNull();
    }

    @Test
    void shouldPostBalancedEntries() {

        LedgerTransaction transaction = transfer("NGN");
        Money amount = Money.ofMinor(2_000_000, NGN);

        LedgerEntry debit = LedgerEntry.debit(transaction, esther, amount);
        LedgerEntry credit = LedgerEntry.credit(transaction, john, amount);

        transaction.post(List.of(debit, credit));

        assertThat(transaction.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
        assertThat(transaction.getPostedAt()).isNotNull();
        assertThat(debit.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(debit.getAmountMinor()).isEqualTo(2_000_000);
        assertThat(debit.getAccount()).isSameAs(esther);
        assertThat(credit.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(credit.getAccount()).isSameAs(john);
    }

    @Test
    void shouldRefuseToPostUnbalancedEntries() {

        LedgerTransaction transaction = transfer("NGN");

        LedgerEntry debit = LedgerEntry.debit(transaction, esther, Money.ofMinor(2_000_000, NGN));
        LedgerEntry credit = LedgerEntry.credit(transaction, john, Money.ofMinor(1_900_000, NGN));

        assertThatThrownBy(() -> transaction.post(List.of(debit, credit)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not balanced");

        assertThat(transaction.getStatus()).isEqualTo(LedgerTransactionStatus.INITIATED);
    }

    @Test
    void shouldRefuseToPostFewerThanTwoEntries() {

        LedgerTransaction transaction = transfer("NGN");
        LedgerEntry credit = LedgerEntry.credit(transaction, john, Money.ofMinor(100, NGN));

        assertThatThrownBy(() -> transaction.post(List.of(credit)))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> transaction.post(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRefuseEntriesOfAnotherTransaction() {

        LedgerTransaction transaction = transfer("NGN");
        LedgerTransaction other = transfer("NGN");
        Money amount = Money.ofMinor(100, NGN);

        List<LedgerEntry> entries = List.of(
                LedgerEntry.debit(transaction, esther, amount),
                LedgerEntry.credit(other, john, amount)
        );

        assertThatThrownBy(() -> transaction.post(entries))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void entriesMustBePositive() {

        LedgerTransaction transaction = transfer("NGN");
        Money zero = Money.ofMinor(0, NGN);

        assertThatThrownBy(() -> LedgerEntry.debit(transaction, esther, zero))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LedgerEntry.credit(transaction, john, zero))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entryCurrencyMustMatchTransactionAndAccount() {

        Money dollars = Money.ofMinor(100, Currency.of("USD"));

        assertThatThrownBy(() -> LedgerEntry.debit(transfer("NGN"), esther, dollars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction currency");

        // A USD transaction cannot touch an NGN account.
        assertThatThrownBy(() -> LedgerEntry.debit(transfer("USD"), esther, dollars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account currency");
    }

    @Test
    void shouldReverseOnlyAPostedTransaction() {

        LedgerTransaction transaction = transfer("NGN");

        assertThatThrownBy(transaction::markReversed)
                .isInstanceOf(IllegalStateException.class);

        post(transaction);
        transaction.markReversed();

        assertThat(transaction.getStatus()).isEqualTo(LedgerTransactionStatus.REVERSED);
        assertThat(transaction.getReversedAt()).isNotNull();
        assertThat(transaction.getPostedAt()).isNotNull();

        assertThatThrownBy(transaction::markReversed)
                .isInstanceOf(IllegalStateException.class);
    }


    @Test
    void reversalShouldBeLinkedToThePostedOriginal() {

        LedgerTransaction original = posted();

        LedgerTransaction reversal = staffReversal(original, "key-rev-1");

        assertThat(reversal.getType()).isEqualTo(LedgerTransactionType.REVERSAL);
        assertThat(reversal.getReversesTransactionId()).isEqualTo(original.getId());
        assertThat(reversal.getCurrency()).isEqualTo("NGN");
        assertThat(reversal.getStatus()).isEqualTo(LedgerTransactionStatus.INITIATED);
        assertThat(original.isReversible()).isTrue();
        assertThat(reversal.isReversible()).isFalse();
    }

    @Test
    void onlyAPostedNonReversalTransactionCanBeReversed() {

        LedgerTransaction initiated = transfer("NGN");
        ReflectionTestUtils.setField(initiated, "id", UUID.randomUUID());

        assertThatThrownBy(() -> staffReversal(initiated, "key-rev-2"))
                .isInstanceOf(IllegalStateException.class);

        LedgerTransaction reversed = posted();
        reversed.markReversed();

        assertThat(reversed.isReversible()).isFalse();
        assertThatThrownBy(() -> staffReversal(reversed, "key-rev-3"))
                .isInstanceOf(IllegalStateException.class);

        LedgerTransaction reversal = staffReversal(posted(), "key-rev-4");
        ReflectionTestUtils.setField(reversal, "id", UUID.randomUUID());
        Money amount = Money.ofMinor(100, NGN);
        reversal.post(List.of(
                LedgerEntry.debit(reversal, john, amount),
                LedgerEntry.credit(reversal, esther, amount)
        ));

        assertThatThrownBy(() -> staffReversal(reversal, "key-rev-5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot itself be reversed");
    }

    @Test
    void staffCorrectionsNeedANoteAndASecondOfficer() {

        UUID maker = UUID.randomUUID();
        UUID checker = UUID.randomUUID();

        LedgerTransaction adjustment =
                LedgerTransaction.createAdjustment("TXN-A", "NGN", maker, checker, "key-adj-1", "Adjustment", "Fee refund");

        assertThat(adjustment.getType()).isEqualTo(LedgerTransactionType.ADJUSTMENT);
        assertThat(adjustment.getInitiatedBy()).isEqualTo(maker);
        assertThat(adjustment.getApprovedBy()).isEqualTo(checker);
        assertThat(adjustment.getStaffNote()).isEqualTo("Fee refund");

        // Four eyes: the maker cannot approve their own correction.
        assertThatThrownBy(() -> LedgerTransaction.createAdjustment(
                "TXN-A", "NGN", maker, maker, "key-adj-2", "Adjustment", "Fee refund"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LedgerTransaction.createAdjustment(
                "TXN-A", "NGN", maker, checker, "key-adj-3", "Adjustment", " "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staff note");

        LedgerTransaction original = posted();

        assertThatThrownBy(() -> LedgerTransaction.createReversal(
                "TXN-R", original, maker, checker, "key-adj-4", "Reversal of TXN-1", null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LedgerTransaction.createReversal(
                "TXN-R", original, maker, maker, "key-adj-5", "Reversal of TXN-1", "Wrong account"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(transfer("NGN").getStaffNote()).isNull();
        assertThat(transfer("NGN").getApprovedBy()).isNull();
    }

    @Test
    void providerDepositRecordsTheProviderInsteadOfAStaffNote() {

        UUID customerId = UUID.randomUUID();

        LedgerTransaction deposit = LedgerTransaction.createProviderDeposit(
                "TXN-P", "NGN", customerId, "key-topup-1", "Top-up", "SIMULATED", "SIMPAY-1"
        );

        assertThat(deposit.getType()).isEqualTo(LedgerTransactionType.DEPOSIT);
        assertThat(deposit.getStatus()).isEqualTo(LedgerTransactionStatus.INITIATED);
        assertThat(deposit.getInitiatedBy()).isEqualTo(customerId);
        assertThat(deposit.getStaffNote()).isNull();
        assertThat(deposit.getProvider()).isEqualTo("SIMULATED");
        assertThat(deposit.getProviderReference()).isEqualTo("SIMPAY-1");

        assertThat(transfer("NGN").getProvider()).isNull();
        assertThat(transfer("NGN").getProviderReference()).isNull();
    }

    @Test
    void providerTransactionsNeedTheProviderAndItsReference() {

        assertThatThrownBy(() -> LedgerTransaction.createProviderDeposit(
                "TXN-P", "NGN", UUID.randomUUID(), "key-topup-2", "Top-up", null, "SIMPAY-1"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LedgerTransaction.createInboundTransfer(
                "TXN-I", "NGN", "Rent", "SIMULATED", " ", SENDER
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inboundTransferHasNoInitiatorAndNamesTheSender() {

        LedgerTransaction inbound =
                LedgerTransaction.createInboundTransfer("TXN-I", "NGN", "Rent", "SIMULATED", "999001SESSION", SENDER);

        assertThat(inbound.getType()).isEqualTo(LedgerTransactionType.INBOUND_TRANSFER);
        assertThat(inbound.getInitiatedBy()).isNull();
        assertThat(inbound.getIdempotencyKey()).isEqualTo("999001SESSION");
        assertThat(inbound.getProviderReference()).isEqualTo("999001SESSION");
        assertThat(inbound.getCounterparty()).isEqualTo(SENDER);
    }

    @Test
    void railReversalOnlyUndoesAnOutboundTransfer() {

        UUID customerId = UUID.randomUUID();

        LedgerTransaction outbound =
                LedgerTransaction.createOutboundTransfer(
                        "TXN-O", "NGN", customerId, "key-out-1", "Transfer to Chiamaka Obi",
                        "SIMULATED", "100999SESSION", SENDER
                );
        ReflectionTestUtils.setField(outbound, "id", UUID.randomUUID());
        post(outbound);

        LedgerTransaction reversal =
                LedgerTransaction.createRailReversal("TXN-R", outbound, "rail-1", "Reversal", "REV-100999SESSION");

        assertThat(reversal.getInitiatedBy()).isEqualTo(customerId);
        assertThat(reversal.getProvider()).isEqualTo("SIMULATED");
        assertThat(reversal.getStaffNote()).isNull();
        assertThat(reversal.getCounterparty()).isEqualTo(SENDER);

        assertThatThrownBy(() -> LedgerTransaction.createRailReversal("TXN-R", posted(), "rail-2", "Reversal", "REV-X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotPostTwice() {

        LedgerTransaction transaction = transfer("NGN");
        List<LedgerEntry> entries = post(transaction);

        assertThatThrownBy(() -> transaction.post(entries))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectMalformedCurrency() {

        assertThatThrownBy(() -> transfer("ngn"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final Counterparty SENDER = new Counterparty("Chiamaka Obi", "Test Bank", "7123456789");

    private List<LedgerEntry> post(LedgerTransaction transaction) {

        Money amount = Money.ofMinor(100, NGN);
        List<LedgerEntry> entries = List.of(
                LedgerEntry.debit(transaction, esther, amount),
                LedgerEntry.credit(transaction, john, amount)
        );

        transaction.post(entries);

        return entries;
    }

    /** A posted transfer with an id, ready to be reversed. */
    private LedgerTransaction posted() {

        LedgerTransaction transaction = transfer("NGN");
        ReflectionTestUtils.setField(transaction, "id", UUID.randomUUID());
        post(transaction);
        return transaction;
    }

    private static LedgerTransaction staffReversal(LedgerTransaction original, String key) {

        return LedgerTransaction.createReversal(
                "TXN-R", original, UUID.randomUUID(), UUID.randomUUID(), key,
                "Reversal of TXN-1", "Sent to the wrong account"
        );
    }

    private static LedgerTransaction transfer(String currency) {
        return LedgerTransaction.createTransfer("TXN-1", currency, UUID.randomUUID(), "key-00001", null);
    }

    private static Account account(String firstName, String accountNumber) {

        Customer customer = Customer.create(
                firstName,
                "Test",
                firstName.toLowerCase() + "@example.com",
                "+2348012345678"
        );
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());

        Account account = Account.open(customer, accountNumber, AccountType.PERSONAL, "NGN");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());

        return account;
    }
}
