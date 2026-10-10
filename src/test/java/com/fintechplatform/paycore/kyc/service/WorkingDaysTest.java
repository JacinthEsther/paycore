package com.fintechplatform.paycore.kyc.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingDaysTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Test
    void shouldSkipTheWeekend() {

        Instant thursdayMorning = Instant.parse("2026-10-08T09:00:00Z");

        assertThat(WorkingDays.after(thursdayMorning, 3, LAGOS))
                .isEqualTo(Instant.parse("2026-10-13T09:00:00Z"));
    }

    @Test
    void aRequestMadeOnSaturdayShouldStartCountingOnMonday() {

        Instant saturday = Instant.parse("2026-10-10T12:00:00Z");

        assertThat(WorkingDays.after(saturday, 1, LAGOS))
                .isEqualTo(Instant.parse("2026-10-12T12:00:00Z"));
    }

    @Test
    void zeroWorkingDaysShouldMeanNow() {

        Instant now = Instant.parse("2026-10-07T15:30:00Z");

        assertThat(WorkingDays.after(now, 0, LAGOS)).isEqualTo(now);
    }
}
