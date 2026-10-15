package com.nexbank.app.core.config

import android.content.Context
//import com.google.firebase.BuildConfig
import com.nexbank.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class Country(val code: String, val displayName: String, val currencyCode: String) {
    UK("GB", "United Kingdom", "GBP"),
    MEXICO("MX", "México", "MXN"),
    INDIA("IN", "India", "INR"),
}

enum class Feature { PAYMENT_TRACKER, TRANSACTION_HISTORY, E_STATEMENT, CONTACT_DETAILS, LOST_AND_STOLEN, HELP_CENTER }
enum class LostCardReason { LOST, STOLEN }

/**
 * ONE code base, MANY markets: every country-specific rule lives here as data.
 * Features read this instead of `if (country == ...)` scattered through the UI.
 */
data class CountryConfig(
    val country: Country,
    val features: Set<Feature>,
    val postcodeRegex: Regex,
    val phoneRegex: Regex,
    val lostCardReasons: List<LostCardReason>,
    val lostStolenRequiresDeclaration: Boolean,
    val statementRetentionMonths: Int,
) {
    fun isEnabled(feature: Feature) = feature in features

    companion object {
        private val ic = RegexOption.IGNORE_CASE

        fun forCountry(country: Country): CountryConfig = when (country) {
            Country.UK -> CountryConfig(
                country = country,
                features = setOf(Feature.PAYMENT_TRACKER, Feature.TRANSACTION_HISTORY, Feature.E_STATEMENT, Feature.CONTACT_DETAILS, Feature.HELP_CENTER),
                postcodeRegex = Regex("^[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}$", ic),
                phoneRegex = Regex("^(\\+44|0)7\\d{9}$"),
                lostCardReasons = emptyList(),
                lostStolenRequiresDeclaration = false,
                statementRetentionMonths = 84,
            )
            Country.MEXICO -> CountryConfig(
                country = country,
                features = setOf(Feature.LOST_AND_STOLEN, Feature.TRANSACTION_HISTORY, Feature.E_STATEMENT, Feature.HELP_CENTER),
                postcodeRegex = Regex("^\\d{5}$"),
                phoneRegex = Regex("^(\\+52)?\\d{10}$"),
                lostCardReasons = listOf(LostCardReason.LOST, LostCardReason.STOLEN),
                lostStolenRequiresDeclaration = true,
                statementRetentionMonths = 60,
            )
            Country.INDIA -> CountryConfig(
                country = country,
                features = setOf(Feature.LOST_AND_STOLEN, Feature.TRANSACTION_HISTORY, Feature.E_STATEMENT, Feature.CONTACT_DETAILS, Feature.HELP_CENTER),
                postcodeRegex = Regex("^\\d{6}$"),
                phoneRegex = Regex("^(\\+91)?[6-9]\\d{9}$"),
                lostCardReasons = listOf(LostCardReason.LOST, LostCardReason.STOLEN),
                lostStolenRequiresDeclaration = false,
                statementRetentionMonths = 12,
            )
        }
    }
}

/** Holds the active market. Flavors pick the default; users/testers can switch at runtime. */
@Singleton
class CountryConfigProvider @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("nex_settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(CountryConfig.forCountry(load()))

    val config: StateFlow<CountryConfig> = _config.asStateFlow()
    val current: CountryConfig get() = _config.value

    fun select(country: Country) {
        prefs.edit().putString(KEY, country.name).apply()
        _config.value = CountryConfig.forCountry(country)
    }

    private fun load(): Country =
        runCatching { Country.valueOf(prefs.getString(KEY, null) ?: BuildConfig.DEFAULT_COUNTRY) }.getOrDefault(Country.UK)

    private companion object { const val KEY = "country" }
}
