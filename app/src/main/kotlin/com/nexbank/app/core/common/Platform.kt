package com.nexbank.app.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Inject dispatchers so tests can swap them (never hard-code Dispatchers.IO in business code). */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io = Dispatchers.IO
    override val default = Dispatchers.Default
    override val main = Dispatchers.Main
}

/** Thin seam over Crashlytics/Sentry. ROTA engineers read `recentErrors()` from the in-app diagnostics sheet. */
interface CrashReporter {
    fun log(message: String)
    fun record(t: Throwable, context: String? = null)
    fun recentErrors(): List<String>
}

@Singleton
class TimberCrashReporter @Inject constructor() : CrashReporter {
    private val buffer = ArrayDeque<String>()

    override fun log(message: String) = Timber.i(message)

    @Synchronized
    override fun record(t: Throwable, context: String?) {
        Timber.e(t, "recorded error %s", context ?: "")
        if (buffer.size >= 50) buffer.removeFirst()
        buffer.addLast("${java.time.Instant.now()} ${t::class.java.simpleName}: ${t.message} ${context ?: ""}".trim())
    }

    @Synchronized
    override fun recentErrors(): List<String> = buffer.toList().asReversed()
}

/** Money is always stored as minor units (pence/centavos/paise) to avoid floating point errors. */
data class Money(val minorUnits: Long, val currency: String) {
    fun format(locale: Locale = Locale.getDefault()): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = Currency.getInstance(this@Money.currency)
        }.format(minorUnits / 100.0)
}
