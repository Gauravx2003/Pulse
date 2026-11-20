package com.pulse.modules.okrs;

import com.pulse.platform.module.PulseModule;

/**
 * Track 2 OKRs module. For now it only registers {@code migrations/V210__okrs.sql} and the demo
 * OKR seed {@code seed/S210__okrs.sql} (task 14.1). The service, API, permissions and the import and
 * export interface for Track 3 arrive with tasks 14.2 and 14.3.
 */
public class OkrsModule implements PulseModule {

    @Override
    public String id() {
        return "okrs";
    }
}
