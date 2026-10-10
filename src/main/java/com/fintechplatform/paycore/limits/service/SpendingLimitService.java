package com.fintechplatform.paycore.limits.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.device.config.DeviceProperties;
import com.fintechplatform.paycore.device.repository.CustomerDeviceRepository;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import com.fintechplatform.paycore.ledger.repository.LedgerEntryRepository;
import com.fintechplatform.paycore.limits.config.TierLimitProperties;
import com.fintechplatform.paycore.limits.config.TierLimitProperties.TierLimit;
import com.fintechplatform.paycore.limits.exception.BalanceLimitExceededException;
import com.fintechplatform.paycore.limits.exception.SpendingLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class SpendingLimitService {

    public static final Set<LedgerTransactionType> CUSTOMER_PAYMENTS =
            EnumSet.of(
                    LedgerTransactionType.TRANSFER,
                    LedgerTransactionType.OUTBOUND_TRANSFER,
                    LedgerTransactionType.BILL_PAYMENT
            );

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm 'WAT'");

    private final CustomerDeviceRepository deviceRepository;
    private final KycProfileRepository kycProfileRepository;
    private final LedgerEntryRepository entryRepository;
    private final DeviceProperties deviceProperties;
    private final TierLimitProperties tierLimits;
    private final ZoneId timeZone;

    public SpendingLimitService(
            CustomerDeviceRepository deviceRepository,
            KycProfileRepository kycProfileRepository,
            LedgerEntryRepository entryRepository,
            DeviceProperties deviceProperties,
            TierLimitProperties tierLimits,
            @Value("${paycore.ledger.statement-time-zone:Africa/Lagos}") ZoneId timeZone
    ) {
        this.deviceRepository = deviceRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.entryRepository = entryRepository;
        this.deviceProperties = deviceProperties;
        this.tierLimits = tierLimits;
        this.timeZone = timeZone;
    }

    public void requireWithinLimits(Account source, Money amount) {

        UUID customerId = source.getCustomer().getId();
        Instant now = Instant.now();

        int tier = tierOf(customerId);
        Money daily = Money.ofMajor(tierLimits.forTier(tier).getDailySend(), amount.currency());

        if (tier == 0) {
            throw new SpendingLimitExceededException(
                    "DAILY", "Verify your identity before sending money.", daily, now
            );
        }

        Instant today = startOfDay(now);

        require(
                "DAILY",
                source,
                amount,
                daily,
                today,
                today.atZone(timeZone).plusDays(1).toInstant(),
                "Your tier " + tier + " limit is %s a day. It resets at %s."
        );

        Optional<Instant> coolingOffUntil = deviceRepository.findCoolingOffUntil(customerId, now);

        if (coolingOffUntil.isPresent()) {
            require(
                    "NEW_DEVICE",
                    source,
                    amount,
                    Money.ofMajor(deviceProperties.getCoolingOffLimit(), amount.currency()),
                    coolingOffUntil.get().minus(deviceProperties.getCoolingOff()),
                    coolingOffUntil.get(),
                    "A new device was added to your profile, so for now you can send at most %s in total until %s."
            );
        }
    }

    public void requireCanHold(Account destination, long balanceMinor, Money amount, boolean ownAccount) {

        int tier = tierOf(destination.getCustomer().getId());
        TierLimit limit = tierLimits.forTier(tier);

        if (limit.getMaxBalance() == null) {
            return;
        }

        Money cap = Money.ofMajor(limit.getMaxBalance(), amount.currency());
        Money balance = Money.ofMinor(balanceMinor, amount.currency());

        if (balance.add(amount).isGreaterThan(cap)) {
            throw new BalanceLimitExceededException(
                    ownAccount
                            ? "A tier " + tier + " account can hold at most " + format(cap)
                              + ". Upgrade your tier to receive more."
                            : "The recipient's account cannot receive this amount."
            );
        }
    }

    public int tierOf(UUID customerId) {
        return kycProfileRepository.findTierByCustomerId(customerId).map(Short::intValue).orElse(0);
    }

    public long sentToday(UUID accountId) {
        return entryRepository.calculateSentSince(accountId, CUSTOMER_PAYMENTS, startOfDay(Instant.now()));
    }

    public Instant endOfToday() {
        return startOfDay(Instant.now()).atZone(timeZone).plusDays(1).toInstant();
    }

    public TierLimit limitsFor(int tier) {
        return tierLimits.forTier(tier);
    }

    private Instant startOfDay(Instant now) {
        return LocalDate.ofInstant(now, timeZone).atStartOfDay(timeZone).toInstant();
    }

    private void require(
            String limit,
            Account source,
            Money amount,
            Money cap,
            Instant since,
            Instant resetsAt,
            String message
    ) {

        Money sent =
                Money.ofMinor(
                        entryRepository.calculateSentSince(source.getId(), CUSTOMER_PAYMENTS, since),
                        amount.currency()
                );

        if (sent.add(amount).isGreaterThan(cap)) {
            Money remaining = cap.isGreaterThan(sent) ? cap.subtract(sent) : Money.ofMinor(0, amount.currency());

            throw new SpendingLimitExceededException(
                    limit,
                    message.formatted(format(cap), WHEN.withZone(timeZone).format(resetsAt))
                            + " You can still send " + format(remaining) + ".",
                    remaining,
                    resetsAt
            );
        }
    }

    private static String format(Money money) {
        return money.currency().code() + " " + money.toMajor().toPlainString();
    }
}
