package com.bank.compliance.domain;

/** The compliance-owned name lists a screening matches against. */
public enum ScreeningList {
    SANCTIONS("sanctions list"),
    PEP("PEP register");

    private final String displayName;

    ScreeningList(String displayName) {
        this.displayName = displayName;
    }

    /** How the list is named in messages ("sanctions list", "PEP register"). */
    public String displayName() {
        return displayName;
    }
}
