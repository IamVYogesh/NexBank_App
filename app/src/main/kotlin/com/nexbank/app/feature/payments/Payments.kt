package com.nexbank.app.feature.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.common.Money
import com.nexbank.app.core.common.safeCall
import com.nexbank.app.core.database.PaymentDao
import com.nexbank.app.core.database.PaymentEntity
import com.nexbank.app.core.network.BankingApi
import com.nexbank.app.core.network.PaymentDto
import com.nexbank.designlibrary.ChipTone
import com.nexbank.designlibrary.NexCard
import com.nexbank.designlibrary.NexEmptyState
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexStatusChip
import com.nexbank.designlibrary.NexTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import javax.inject.Inject

// ---------- domain ----------
enum class PaymentStatus {
    SUBMITTED, PROCESSING, DELIVERED, FAILED;
    companion object { fun from(raw: String) = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PROCESSING }
}

data class Payment(val id: String, val payee: String, val amount: Money, val status: PaymentStatus, val createdAt: Instant, val expectedBy: Instant?)

interface PaymentRepository {
    fun observePayments(): Flow<List<Payment>>
    suspend fun refresh(): AppResult<Unit>
}

class ObservePaymentsUseCase @Inject constructor(private val repo: PaymentRepository) { operator fun invoke() = repo.observePayments() }
class RefreshPaymentsUseCase @Inject constructor(private val repo: PaymentRepository) { suspend operator fun invoke() = repo.refresh() }

// ---------- data (offline-first: Room is the single source of truth) ----------
class PaymentRepositoryImpl @Inject constructor(
    private val api: BankingApi,
    private val dao: PaymentDao,
    private val dispatchers: DispatcherProvider,
    private val crash: CrashReporter,
) : PaymentRepository {
    override fun observePayments(): Flow<List<Payment>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun refresh(): AppResult<Unit> = safeCall(dispatchers.io, crash) {
        dao.replaceAll(api.payments().map { it.toEntity() })
    }
}

internal fun PaymentDto.toEntity() = PaymentEntity(id, payee, amountMinor, currency, status, createdAt, expectedBy)
internal fun PaymentEntity.toDomain() = Payment(id, payee, Money(amountMinor, currency), PaymentStatus.from(status), Instant.ofEpochMilli(createdAt), expectedBy?.let(Instant::ofEpochMilli))

// ---------- presentation ----------
data class PaymentTrackerUiState(val payments: List<Payment> = emptyList(), val isRefreshing: Boolean = false, val error: String? = null)

@HiltViewModel
class PaymentTrackerViewModel @Inject constructor(
    observePayments: ObservePaymentsUseCase,
    private val refreshPayments: RefreshPaymentsUseCase,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    /** Reactive programming: three streams are combined into one immutable UI state. */
    val uiState: StateFlow<PaymentTrackerUiState> = combine(observePayments(), refreshing, error) { p, r, e ->
        PaymentTrackerUiState(p, r, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentTrackerUiState(isRefreshing = true))

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            error.value = (refreshPayments() as? AppResult.Failure)?.error?.userMessage
            refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentTrackerScreen(onBack: () -> Unit, viewModel: PaymentTrackerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { NexTopBar("Payment Tracker", onBack) }) { padding ->
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.payments.isEmpty() && !state.isRefreshing) {
                NexEmptyState(state.error ?: "No payments yet")
            } else {
                LazyColumn(contentPadding = PaddingValues(NexSpacing.md), verticalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
                    state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(state.payments, key = { it.id }) { PaymentRow(it) }
                }
            }
        }
    }
}

@Composable
private fun PaymentRow(p: Payment) {
    NexCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(p.payee, style = MaterialTheme.typography.titleMedium)
                Text(DateFormat.getDateInstance().format(Date.from(p.createdAt)), style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(p.amount.format(), style = MaterialTheme.typography.titleMedium)
                NexStatusChip(p.status.name.lowercase().replaceFirstChar(Char::uppercase), p.status.tone())
            }
        }
        p.expectedBy?.takeIf { p.status == PaymentStatus.PROCESSING || p.status == PaymentStatus.SUBMITTED }?.let {
            Text("Expected by ${DateFormat.getDateInstance().format(Date.from(it))}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun PaymentStatus.tone() = when (this) {
    PaymentStatus.DELIVERED -> ChipTone.Success
    PaymentStatus.PROCESSING, PaymentStatus.SUBMITTED -> ChipTone.Warning
    PaymentStatus.FAILED -> ChipTone.Danger
}
