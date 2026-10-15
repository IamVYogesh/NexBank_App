package com.nexbank.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nexbank.app.di.dagger2.AuditLogger
import com.nexbank.app.di.dagger2.DaggerAuditComponent
import com.nexbank.app.feature.statements.Notifications
import com.nexbank.app.feature.statements.StatementSyncWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class NexBankApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Plain Dagger 2 component living next to Hilt (see di/dagger2). Hilt re-exports it via AppModule. */
    val auditLogger: AuditLogger by lazy { DaggerAuditComponent.factory().create(this).auditLogger() }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Notifications.createChannels(this)
        StatementSyncWorker.schedule(this)
        auditLogger.log("app_start market=${BuildConfig.DEFAULT_COUNTRY}")
    }
}
