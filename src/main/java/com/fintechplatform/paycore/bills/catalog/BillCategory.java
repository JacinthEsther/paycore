package com.fintechplatform.paycore.bills.catalog;

public enum BillCategory {

    AIRTIME("Airtime", "Phone number", "0\\d{10}"),
    DATA("Data", "Phone number", "0\\d{10}"),
    ELECTRICITY("Electricity", "Meter number", "\\d{11,13}"),
    TV("TV", "Smartcard number", "\\d{10,12}");

    private final String label;
    private final String numberLabel;
    private final String numberPattern;

    BillCategory(String label, String numberLabel, String numberPattern) {
        this.label = label;
        this.numberLabel = numberLabel;
        this.numberPattern = numberPattern;
    }

    public String label() {
        return label;
    }

    public String numberLabel() {
        return numberLabel;
    }

    public boolean acceptsNumber(String number) {
        return number != null && number.matches(numberPattern);
    }
}
