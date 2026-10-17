package com.apnaservo.partner;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppControlRepositoryTest {
    @Test public void defaultsKeepCriticalAppUsable() {
        AppControlRepository.AppConfig config = AppControlRepository.AppConfig.defaults();
        assertFalse(config.maintenance());
        for (String id : AppControlRepository.FEATURE_IDS) assertTrue(config.featureEnabled(id));
    }

    @Test public void forceUpdateUsesMinimumVersion() {
        AppControlRepository.AppConfig config = AppControlRepository.AppConfig.defaults();
        config.updateEnabled = true;
        config.updateType = "force";
        config.minimumVersion = "1.0.20";
        assertTrue(config.forceUpdate("1.0.19"));
        assertFalse(config.forceUpdate("1.0.20"));
    }

    @Test public void softUpdateAllowsCurrentMinimum() {
        AppControlRepository.AppConfig config = AppControlRepository.AppConfig.defaults();
        config.updateEnabled = true;
        config.updateType = "soft";
        config.minimumVersion = "1.0.18";
        config.latestVersion = "1.1.0";
        assertFalse(config.forceUpdate("1.0.19"));
        assertTrue(config.softUpdate("1.0.19"));
    }
}
