package com.nexbank.app.feature.statements

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.designlibrary.ChipTone
import com.nexbank.designlibrary.NexCard
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexStatusChip
import com.nexbank.designlibrary.NexTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class StatementItem(val statement: Statement, val download: DownloadState?)
data class StatementsUiState(val items: List<StatementItem> = emptyList(), val isLoading: Boolean = true)
sealed interface StatementsEvent {
    data class Message(val text: String) : StatementsEvent
    data class OpenPdf(val file: File) : StatementsEvent
}

/** E-Statement feature (code owner area): list -> download (foreground service) -> open decrypted PDF. */
@HiltViewModel
class StatementsViewModel @Inject constructor(
    private val repository: StatementRepository,
    private val tracker: DownloadTracker,
    private val launcher: StatementDownloadLauncher,
    private val files: StatementFileStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _events = Channel<StatementsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<StatementsUiState> = combine(repository.observeStatements(), tracker.states) { list, downloads ->
        StatementsUiState(list.map { StatementItem(it, downloads[it.id]) }, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatementsUiState())

    init {
        viewModelScope.launch {
            (repository.refresh() as? AppResult.Failure)?.let { _events.send(StatementsEvent.Message(it.error.userMessage)) }
        }
        viewModelScope.launch {
            // Surface download failures once, then forget them so the row can be retried.
            tracker.states.collect { states ->
                states.forEach { (id, s) ->
                    if (s is DownloadState.Failed) { _events.send(StatementsEvent.Message(s.error.userMessage)); tracker.clear(id) }
                    if (s is DownloadState.Done) tracker.clear(id)
                }
            }
        }
    }

    fun download(id: String) = launcher.start(id)
    fun cancel(id: String) = launcher.cancel(id)

    fun open(id: String) {
        viewModelScope.launch {
            // Decryption is disk + CPU work: keep it off the main thread.
            val file = runCatching { withContext(dispatchers.io) { files.decryptForViewing(id) } }.getOrNull()
            _events.send(if (file != null) StatementsEvent.OpenPdf(file) else StatementsEvent.Message("Could not open statement. Please download it again."))
        }
    }
}

@Composable
fun StatementsScreen(onBack: () -> Unit, viewModel: StatementsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingId by remember { mutableStateOf<String?>(null) }

    // Android 13+: runtime permission for notifications. The download still runs if denied.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingId?.let(viewModel::download); pendingId = null
    }
    fun startDownload(id: String) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) { pendingId = id; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } else viewModel.download(id)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is StatementsEvent.Message -> snackbar.showSnackbar(e.text)
                is StatementsEvent.OpenPdf -> {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", e.file)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(intent) }.onFailure { snackbar.showSnackbar("No PDF viewer installed") }
                }
            }
        }
    }

    Scaffold(topBar = { NexTopBar("E-Statements", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(NexSpacing.md), verticalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
            items(state.items, key = { it.statement.id }) { item ->
                val s = item.statement
                NexCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        when (val d = item.download) {
                            is DownloadState.Progress -> TextButton(onClick = { viewModel.cancel(s.id) }) { Text("Cancel") }
                            else -> if (s.isDownloaded) TextButton(onClick = { viewModel.open(s.id) }) { Text("Open") }
                            else TextButton(onClick = { startDownload(s.id) }) { Text("Download") }
                        }
                    }
                    when (val d = item.download) {
                        is DownloadState.Progress -> LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        else -> NexStatusChip(if (s.isDownloaded) "Downloaded" else "${s.sizeBytes / 1_000_000} MB", if (s.isDownloaded) ChipTone.Success else ChipTone.Neutral)
                    }
                }
            }
        }
    }
}
