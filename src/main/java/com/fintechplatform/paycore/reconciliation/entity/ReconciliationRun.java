package com.fintechplatform.paycore.reconciliation.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.reconciliation.enums.ProviderKind;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @UuidV7
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_kind", nullable = false, length = 20, updatable = false)
    private ProviderKind providerKind;

    @Column(nullable = false, length = 30, updatable = false)
    private String provider;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "provider_lines", nullable = false, updatable = false)
    private int providerLines;

    @Column(name = "paycore_records", nullable = false, updatable = false)
    private int paycoreRecords;

    @Column(nullable = false, updatable = false)
    private int matched;

    @Column(nullable = false, updatable = false)
    private int breaks;

    @Column(name = "provider_total_minor", nullable = false, updatable = false)
    private long providerTotalMinor;

    @Column(name = "paycore_total_minor", nullable = false, updatable = false)
    private long paycoreTotalMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(
            ProviderKind providerKind,
            String provider,
            LocalDate businessDate,
            Instant windowStart,
            Instant windowEnd,
            UUID startedBy,
            int providerLines,
            int paycoreRecords,
            int matched,
            int breaks,
            long providerTotalMinor,
            long paycoreTotalMinor
    ) {
        this.providerKind = Objects.requireNonNull(providerKind, "providerKind");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        this.startedBy = startedBy;
        this.providerLines = providerLines;
        this.paycoreRecords = paycoreRecords;
        this.matched = matched;
        this.breaks = breaks;
        this.providerTotalMinor = providerTotalMinor;
        this.paycoreTotalMinor = paycoreTotalMinor;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ProviderKind getProviderKind() {
        return providerKind;
    }

    public String getProvider() {
        return provider;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public int getProviderLines() {
        return providerLines;
    }

    public int getPaycoreRecords() {
        return paycoreRecords;
    }

    public int getMatched() {
        return matched;
    }

    public int getBreaks() {
        return breaks;
    }

    public long getProviderTotalMinor() {
        return providerTotalMinor;
    }

    public long getPaycoreTotalMinor() {
        return paycoreTotalMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
