package com.fintechplatform.paycore.bills.catalog;

import com.fintechplatform.paycore.bills.exception.UnknownBillerException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public final class BillCatalog {

    public static final List<String> METER_TYPES = List.of("prepaid", "postpaid");

    private static final BigDecimal AIRTIME_MIN = new BigDecimal("50");
    private static final BigDecimal AIRTIME_MAX = new BigDecimal("50000");
    private static final BigDecimal POWER_MIN = new BigDecimal("1000");
    private static final BigDecimal POWER_MAX = new BigDecimal("500000");

    private static final List<Biller> BILLERS = List.of(
            airtime("mtn", "MTN"),
            airtime("glo", "Glo"),
            airtime("airtel", "Airtel"),
            airtime("etisalat", "9mobile"),

            data("mtn-data", "MTN Data"),
            data("glo-data", "Glo Data"),
            data("airtel-data", "Airtel Data"),
            data("etisalat-data", "9mobile Data"),

            power("ikeja-electric", "Ikeja Electric (IKEDC)"),
            power("eko-electric", "Eko Electric (EKEDC)"),
            power("abuja-electric", "Abuja Electric (AEDC)"),

            tv("dstv", "DStv"),
            tv("gotv", "GOtv"),
            tv("startimes", "StarTimes")
    );

    private BillCatalog() {
    }

    public static List<Biller> billers() {
        return BILLERS;
    }

    public static Optional<Biller> find(String id) {
        return BILLERS.stream().filter(biller -> biller.id().equals(id)).findFirst();
    }

    public static Biller require(String id) {
        return find(id).orElseThrow(() -> new UnknownBillerException(id));
    }

    private static Biller airtime(String id, String name) {
        return new Biller(id, name, BillCategory.AIRTIME, false, false, AIRTIME_MIN, AIRTIME_MAX);
    }

    private static Biller data(String id, String name) {
        return new Biller(id, name, BillCategory.DATA, true, false, null, null);
    }

    private static Biller power(String id, String name) {
        return new Biller(id, name, BillCategory.ELECTRICITY, false, true, POWER_MIN, POWER_MAX);
    }

    private static Biller tv(String id, String name) {
        return new Biller(id, name, BillCategory.TV, true, true, null, null);
    }
}
