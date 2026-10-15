package com.nexbank.app.di.dagger2

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DAGGER 2 (without Hilt): hand-written @Component, @Module, @Provides, @BindsInstance and a factory.
 * Hilt generates exactly this kind of code for you - reading this file shows what Hilt hides.
 */
@Singleton
@Component(modules = [AuditModule::class])
interface AuditComponent {
    fun auditLogger(): AuditLogger

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance context: Context): AuditComponent
    }
}

@Module
@dagger.hilt.migration.DisableInstallInCheck
object AuditModule {
    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()
}

@Singleton
class AuditLogger @Inject constructor(private val clock: Clock, private val context: Context) {
    fun log(event: String) = Timber.i("AUDIT %s [%s] %s", clock.instant(), context.packageName, event)
}
