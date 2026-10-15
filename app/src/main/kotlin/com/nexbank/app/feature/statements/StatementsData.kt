package com.nexbank.app.feature.statements

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.common.safeCall
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.core.database.StatementDao
import com.nexbank.app.core.database.StatementEntity
import com.nexbank.app.core.network.BankingApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import java.io.File
import java.io.OutputStream
import java.time.Month
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ---------- domain ----------
data class Statement(val id: String, val year: Int, val month: Int, val sizeBytes: Long, val downloadedAt: Long?) {
    val title: String get() = "${Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())} $year"
    val isDownloaded get() = downloadedAt != null
}

sealed interface DownloadState {
    data class Progress(val bytesRead: Long, val totalBytes: Long) : DownloadState {
        val percent: Int get() = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }
    data object Done : DownloadState
    data class Failed(val error: AppError) : DownloadState
}

interface StatementRepository {
    fun observeStatements(): Flow<List<Statement>>
    suspend fun refresh(): AppResult<Unit>
    suspend fun markDownloaded(id: String)
    suspend fun pruneOlderThan(months: Int)
}

/** Where encrypted PDFs live. Interface => unit tests use an in-memory fake (no Keystore needed). */
interface StatementFileStore {
    fun openOutput(id: String): OutputStream
    fun exists(id: String): Boolean
    fun delete(id: String)
    /** Decrypts to a temporary cache file so a PDF viewer can open it. */
    fun decryptForViewing(id: String): File
    fun clearViewCache()
}

// ---------- data ----------
class StatementRepositoryImpl @Inject constructor(
    private val api: BankingApi,
    private val dao: StatementDao,
    private val files: StatementFileStore,
    private val dispatchers: DispatcherProvider,
    private val crash: CrashReporter,
) : StatementRepository {
    override fun observeStatements() = dao.observeAll().map { rows -> rows.map { Statement(it.id, it.year, it.month, it.sizeBytes, it.downloadedAt) } }

    override suspend fun refresh() = safeCall(dispatchers.io, crash) {
        dao.insertIgnore(api.statements().map { StatementEntity(it.id, it.year, it.month, it.sizeBytes, null) })
    }

    override suspend fun markDownloaded(id: String) = dao.markDownloaded(id, System.currentTimeMillis())

    override suspend fun pruneOlderThan(months: Int) {
        val cal = Calendar.getInstance()
        val cutoff = cal.get(Calendar.YEAR) * 12 + (cal.get(Calendar.MONTH) + 1) - months
        val old = dao.olderThan(cutoff)
        old.forEach { files.delete(it.id) }
        if (old.isNotEmpty()) dao.deleteAll(old.map { it.id })
    }
}

/**
 * Streams a statement PDF to encrypted storage and reports progress as a cold Flow.
 * - Runs on the IO dispatcher via flowOn (the collector stays on its own dispatcher).
 * - Cancelling the collector cancels the download and deletes the partial file (PROD_BUGS #2).
 */
class StatementDownloader @Inject constructor(
    private val api: BankingApi,
    private val files: StatementFileStore,
    private val dispatchers: DispatcherProvider,
    private val crash: CrashReporter,
) {
    fun download(id: String): Flow<DownloadState> = flow {
        var success = false
        try {
            val body = api.downloadStatement(id)
            val total = body.contentLength()
            var read = 0L
            emit(DownloadState.Progress(0, total))
            files.openOutput(id).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        read += n
                        val p = DownloadState.Progress(read, total)
                        if (p.percent != lastPercent) { lastPercent = p.percent; emit(p) } // throttle: <=100 emissions
                        yield() // cooperative cancellation point
                    }
                }
            }
            success = true
            emit(DownloadState.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crash.record(e, "statement download $id")
            emit(DownloadState.Failed(if (e is java.io.IOException) AppError.Offline else AppError.Unknown(e)))
        } finally {
            if (!success) files.delete(id) // never leave a half-written PDF behind
        }
    }.flowOn(dispatchers.io)

    private companion object { const val BUFFER = 8 * 1024 }
}

/** Shared in-memory download progress so the foreground service and the UI see the same state. */
@Singleton
class DownloadTracker @Inject constructor() {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()
    fun update(id: String, s: DownloadState) = _states.update { it + (id to s) }
    fun clear(id: String) = _states.update { it - id }
}

/** Jetpack Security EncryptedFile (AES-256-GCM streaming). */
@Singleton
class EncryptedStatementFileStore @Inject constructor(@ApplicationContext private val context: Context) : StatementFileStore {
    private val dir = File(context.filesDir, "statements").apply { mkdirs() }
    private val viewDir = File(context.cacheDir, "statement_view").apply { mkdirs() }
    private val masterKey by lazy { MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }

    private fun encrypted(file: File) = EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()

    override fun openOutput(id: String): OutputStream {
        val f = File(dir, "$id.enc")
        if (f.exists()) f.delete() // EncryptedFile refuses to overwrite
        return encrypted(f).openFileOutput()
    }

    override fun exists(id: String) = File(dir, "$id.enc").exists()
    override fun delete(id: String) { File(dir, "$id.enc").delete() }

    override fun decryptForViewing(id: String): File {
        val out = File(viewDir, "$id.pdf")
        encrypted(File(dir, "$id.enc")).openFileInput().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    override fun clearViewCache() { viewDir.listFiles()?.forEach { it.delete() } }
}
