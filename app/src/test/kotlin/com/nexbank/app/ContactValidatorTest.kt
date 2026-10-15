package com.nexbank.app

import com.nexbank.app.core.config.Country
import com.nexbank.app.core.config.CountryConfig
import com.nexbank.app.feature.contacts.ContactDetails
import com.nexbank.app.feature.contacts.ContactValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactValidatorTest {
    private val validator = ContactValidator()
    private val uk = CountryConfig.forCountry(Country.UK)
    private val good = ContactDetails("a@b.co", "07123456789", "1 High St", "SW1A 1AA")

    @Test fun `valid UK details pass`() = assertTrue(validator.validate(good, uk).isValid)

    @Test fun `bad email, phone and postcode are reported per field`() {
        val e = validator.validate(good.copy(email = "nope", phone = "123", postcode = "12345"), uk)
        assertNotNull(e.email); assertNotNull(e.phone); assertNotNull(e.postcode); assertNull(e.address)
    }

    @Test fun `postcode rules depend on the market`() {
        val mx = CountryConfig.forCountry(Country.MEXICO)
        assertTrue(validator.validate(good.copy(phone = "5512345678", postcode = "06600"), mx).isValid)
        assertEquals(false, validator.validate(good.copy(phone = "5512345678", postcode = "SW1A 1AA"), mx).isValid)
    }
}
