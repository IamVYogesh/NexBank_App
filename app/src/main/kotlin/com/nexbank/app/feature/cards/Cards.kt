package com.nexbank.app.feature.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.common.safeCall
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.core.config.Feature
import com.nexbank.app.core.config.LostCardReason
import com.nexbank.app.core.database.CardDao
import com.nexbank.app.core.database.CardEntity
import com.nexbank.app.core.network.BankingApi
import com.nexbank.app.core.network.ReportCardRequest
import com.nexbank.designlibrary.ChipTone
import com.nexbank.designlibrary.NexCard
import com.nexbank.designlibrary.NexPrimaryButton
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexStatusChip
import com.nexbank.designlibrary.NexTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------- domain ----------
enum class CardStatus { ACTIVE, BLOCKED, REPLACEMENT_ORDERED }
data class Card(val id: String, val last4: String, val network: String, val status: CardStatus)
data class ReportOutcome(val cardId: String, val replacementEta: String?)

interface CardRepository {
    fun observeCards(): Flow<List<Card>>
    suspend fun refresh(): AppResult<Unit>
    suspend fun reportLostOrStolen(cardId: String, reason: LostCardReason, declarationAccepted: Boolean): AppResult<ReportOutcome>
}

/** "MNS-Mexico Lost and Stolen": business rules are driven by CountryConfig, not by if-country checks. */
class ReportLostOrStolenUseCase @Inject constructor(
    private val repo: CardRepository,
    private val config: CountryConfigProvider,
) {
    suspend operator fun invoke(cardId: String, reason: LostCardReason, declarationAccepted: Boolean): AppResult<ReportOutcome> {
        val cfg = config.current
        if (!cfg.isEnabled(Feature.LOST_AND_STOLEN)) return AppResult.Failure(AppError.Validation("Not available in ${cfg.country.displayName}"))
        if (reason !in cfg.lostCardReasons) return AppResult.Failure(AppError.Validation("This reason is not supported"))
        if (cfg.lostStolenRequiresDeclaration && !declarationAccepted) return AppResult.Failure(AppError.Validation("Please accept the declaration to continue"))
        return repo.reportLostOrStolen(cardId, reason, declarationAccepted)
    }
}

// ---------- data ----------
class CardRepositoryImpl @Inject constructor(
    private val api: BankingApi,
    private val dao: CardDao,
    private val dispatchers: DispatcherProvider,
    private val crash: CrashReporter,
) : CardRepository {
    override fun observeCards() = dao.observeAll().map { rows -> rows.map { Card(it.id, it.last4, it.network, runCatching { CardStatus.valueOf(it.status) }.getOrDefault(CardStatus.ACTIVE)) } }

    override suspend fun refresh() = safeCall(dispatchers.io, crash) {
        dao.replaceAll(api.cards().map { CardEntity(it.id, it.last4, it.network, it.status) })
    }

    override suspend fun reportLostOrStolen(cardId: String, reason: LostCardReason, declarationAccepted: Boolean) =
        safeCall(dispatchers.io, crash) {
            val r = api.reportCard(cardId, ReportCardRequest(reason.name, declarationAccepted))
            dao.updateStatus(cardId, r.status)
            ReportOutcome(r.cardId, r.replacementEta)
        }
}

// ---------- presentation ----------
data class CardsUiState(val cards: List<Card> = emptyList(), val isSubmitting: Boolean = false)
sealed interface CardsEvent { data class Message(val text: String) : CardsEvent; data object ReportDone : CardsEvent }

@HiltViewModel
class CardsViewModel @Inject constructor(
    private val repo: CardRepository,
    private val reportUseCase: ReportLostOrStolenUseCase,
    configProvider: CountryConfigProvider,
) : ViewModel() {
    val config = configProvider.config
    private val submitting = MutableStateFlow(false)
    private val _events = Channel<CardsEvent>(Channel.BUFFERED)
    /** One-off events (snackbars) go through a Channel so they are not replayed on rotation. */
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<CardsUiState> = combine(repo.observeCards(), submitting) { c, s -> CardsUiState(c, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardsUiState())

    init { viewModelScope.launch { repo.refresh() } }

    fun report(cardId: String, reason: LostCardReason, declaration: Boolean) {
        viewModelScope.launch {
            submitting.update { true }
            when (val r = reportUseCase(cardId, reason, declaration)) {
                is AppResult.Success -> {
                    _events.send(CardsEvent.Message("Card blocked. Replacement: ${r.data.replacementEta ?: "soon"}"))
                    _events.send(CardsEvent.ReportDone)
                }
                is AppResult.Failure -> _events.send(CardsEvent.Message(r.error.userMessage))
            }
            submitting.update { false }
        }
    }
}

@Composable
fun CardsScreen(onBack: () -> Unit, viewModel: CardsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var reporting by remember { mutableStateOf<Card?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is CardsEvent.Message -> snackbar.showSnackbar(e.text)
                CardsEvent.ReportDone -> reporting = null
            }
        }
    }

    Scaffold(topBar = { NexTopBar("My Cards", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(NexSpacing.md), verticalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
            items(state.cards, key = { it.id }) { card ->
                NexCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${card.network} •••• ${card.last4}", style = MaterialTheme.typography.titleMedium)
                        NexStatusChip(card.status.name.replace('_', ' ').lowercase(), if (card.status == CardStatus.ACTIVE) ChipTone.Success else ChipTone.Danger)
                    }
                    if (card.status == CardStatus.ACTIVE && config.isEnabled(Feature.LOST_AND_STOLEN)) {
                        TextButton(onClick = { reporting = card }) { Text("Lost or stolen?") }
                    }
                }
            }
        }
    }

    reporting?.let { card ->
        ReportDialog(
            last4 = card.last4,
            reasons = config.lostCardReasons,
            requiresDeclaration = config.lostStolenRequiresDeclaration,
            submitting = state.isSubmitting,
            onDismiss = { reporting = null },
            onSubmit = { reason, declaration -> viewModel.report(card.id, reason, declaration) },
        )
    }
}

@Composable
private fun ReportDialog(
    last4: String,
    reasons: List<LostCardReason>,
    requiresDeclaration: Boolean,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (LostCardReason, Boolean) -> Unit,
) {
    var reason by remember { mutableStateOf(reasons.firstOrNull()) }
    var declared by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block card •••• $last4") },
        text = {
            Column {
                reasons.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r.name.lowercase().replaceFirstChar(Char::uppercase))
                    }
                }
                if (requiresDeclaration) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = declared, onCheckedChange = { declared = it })
                        Text("I confirm this report is true and I did not authorise recent transactions.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { NexPrimaryButton("Block card", onClick = { reason?.let { onSubmit(it, declared) } }, loading = submitting, modifier = Modifier.padding(horizontal = NexSpacing.sm)) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
