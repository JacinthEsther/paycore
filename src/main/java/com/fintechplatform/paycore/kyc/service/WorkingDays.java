package com.fintechplatform.paycore.kyc.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class WorkingDays {

    private WorkingDays() {
    }

    public static Instant after(Instant start, int workingDays, ZoneId zone) {

        ZonedDateTime moment = start.atZone(zone);
        int counted = 0;

        while (counted < workingDays) {
            moment = moment.plusDays(1);
            if (isWorkingDay(moment)) {
                counted++;
            }
        }

        return moment.toInstant();
    }

    private static boolean isWorkingDay(ZonedDateTime moment) {
        DayOfWeek day = moment.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
