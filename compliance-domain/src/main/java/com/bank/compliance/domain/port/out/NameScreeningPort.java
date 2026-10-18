package com.bank.compliance.domain.port.out;

import com.bank.compliance.domain.NormalizedName;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;

/**
 * Matches a normalised name against a compliance-owned screening list and
 * reports the list's current source loads. The shipped adapter matches
 * exactly against the sanctions_list_entry and pep_register_entry tables;
 * a vendor screening service can replace it behind this port.
 */
public interface NameScreeningPort {

    /** @throws ScreeningSourceUnavailableException when the list cannot be read */
    ScreeningListMatch match(ScreeningList list, NormalizedName name);
}
