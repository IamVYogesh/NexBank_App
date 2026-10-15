package com.nexbank.app.feature.statements

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexbank.app.R
import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.config.CountryConfigProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

object Notifications {
    const val CHANNEL_DOWNLOADS = "statement_downloads"
    const val FOREGROUND_ID = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, "Statement downloads", NotificationManager.IMPORTANCE_LOW))
    }

    fun progress(context: Context, title: String, percent: Int, indeterminate: Boolean, cancelIntent: PendingIntent?): Notification =
        NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Preparing…" else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { cancelIntent?.let { addAction(0, "Cancel", it) } }
            .build()
}

/** App-facing API so ViewModels don't touch Context/Intents. */
class StatementDownloadLauncher @Inject constructor(@ApplicationContext private val context: Context) {
    fun start(id: String) = ContextCompat.startForegroundService(context, Intent(context, StatementDownloadService::class.java).putExtra(StatementDownloadService.EXTRA_ID, id))
    fun cancel(id: String) { context.startService(Intent(context, StatementDownloadService::class.java).setAction(StatementDownloadService.ACTION_CANCEL).putExtra(StatementDownloadService.EXTRA_ID, id)) }
}

/**
 * Foreground Service (type dataSync): keeps the download alive when the user leaves the app and shows
 * a progress notification. Android requires startForeground() within ~5s of startForegroundService().
 */
@AndroidEntryPoint
class StatementDownloadService : Service() {
    @Inject lateinit var downloader: StatementDownloader
    @Inject lateinit var tracker: DownloadTracker
    @Inject lateinit var repository: StatementRepository
    @Inject lateinit var dispatchers: DispatcherProvider

    private val job = SupervisorJob()
    private val scope by lazy { CoroutineScope(job + dispatchers.default) }
    private val active = ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        // Must call startForeground first, even if we are about to stop (otherwise: ForegroundServiceDidNotStartInTimeException).
        ServiceCompat.startForeground(this, Notifications.FOREGROUND_ID, buildNotification(id ?: "statement", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (id == null) { stopSelfSafely(); return START_NOT_STICKY }

        if (intent?.action == ACTION_CANCEL) {
            active.remove(id)?.cancel()
            tracker.clear(id)
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (active.containsKey(id)) return START_NOT_STICKY

        // LAZY + start(): guarantees active[id] is set before the coroutine can finish and remove it.
        val downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                downloader.download(id).collect { state ->
                    // Persist "downloaded" BEFORE publishing Done, otherwise the UI flickers back to "Download".
                    if (state is DownloadState.Done) repository.markDownloaded(id)
                    tracker.update(id, state)
                    if (state is DownloadState.Progress) {
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(Notifications.FOREGROUND_ID, buildNotification(id, state.percent, false))
                    }
                }
            } finally {
                active.remove(id)
                stopIfIdle()
            }
        }
        active[id] = downloadJob
        downloadJob.start()
        return START_NOT_STICKY
    }

    private fun buildNotification(id: String, percent: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, id.hashCode(),
            Intent(this, StatementDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notifications.progress(this, "Downloading statement", percent, indeterminate, cancel)
    }

    private fun stopIfIdle() { if (active.isEmpty()) stopSelfSafely() }
    private fun stopSelfSafely() { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }

    override fun onDestroy() { job.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_ID = "statement_id"
        const val ACTION_CANCEL = "com.nexbank.app.CANCEL_DOWNLOAD"
    }
}

/**
 * WorkManager: deferrable, guaranteed background work. Every ~12h (on network) refresh the statement
 * list, apply the market's retention rule, and wipe decrypted PDFs left in the cache.
 */
@HiltWorker
class StatementSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: StatementRepository,
    private val files: StatementFileStore,
    private val config: CountryConfigProvider,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        files.clearViewCache()
        return when (val r = repository.refresh()) {
            is AppResult.Success -> { repository.pruneOlderThan(config.current.statementRetentionMonths); Result.success() }
            is AppResult.Failure -> when (val e = r.error) {
                AppError.Offline -> Result.retry()
                is AppError.Http -> if (e.code >= 500) Result.retry() else Result.failure()
                else -> Result.failure()
            }
        }
    }

    companion object {
        private const val UNIQUE_NAME = "statement_sync"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StatementSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
