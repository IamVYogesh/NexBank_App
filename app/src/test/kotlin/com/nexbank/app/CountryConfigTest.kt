package com.nexbank.app

import com.nexbank.app.core.config.Country
import com.nexbank.app.core.config.CountryConfig
import com.nexbank.app.core.config.Feature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryConfigTest {
    @Test fun `payment tracker is UK only`() {
        assertTrue(CountryConfig.forCountry(Country.UK).isEnabled(Feature.PAYMENT_TRACKER))
        assertFalse(CountryConfig.forCountry(Country.MEXICO).isEnabled(Feature.PAYMENT_TRACKER))
    }

    @Test fun `lost and stolen is enabled for Mexico and needs a declaration`() {
        val mx = CountryConfig.forCountry(Country.MEXICO)
        assertTrue(mx.isEnabled(Feature.LOST_AND_STOLEN)); assertTrue(mx.lostStolenRequiresDeclaration)
    }
}
