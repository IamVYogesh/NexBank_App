package com.nexbank.app.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexbank.app.core.common.Money
import com.nexbank.app.core.network.BankingApi
import com.nexbank.app.core.network.TransactionDto
import com.nexbank.designlibrary.NexCard
import com.nexbank.designlibrary.NexErrorState
import com.nexbank.designlibrary.NexLoadingState
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

data class Transaction(val id: String, val description: String, val amount: Money, val isCredit: Boolean, val postedAt: Instant)

interface TransactionRepository { fun pagedTransactions(): Flow<PagingData<Transaction>> }

class GetPagedTransactionsUseCase @Inject constructor(private val repo: TransactionRepository) { operator fun invoke() = repo.pagedTransactions() }

class TransactionRepositoryImpl @Inject constructor(private val api: BankingApi) : TransactionRepository {
    // GOTCHA: initialLoadSize must equal pageSize because our API is page-number based (not offset based).
    override fun pagedTransactions(): Flow<PagingData<Transaction>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, enablePlaceholders = false)) { TransactionPagingSource(api) }.flow

    private companion object { const val PAGE_SIZE = 20 }
}

class TransactionPagingSource(private val api: BankingApi) : PagingSource<Int, Transaction>() {
    override fun getRefreshKey(state: PagingState<Int, Transaction>): Int? = state.anchorPosition?.let { pos ->
        state.closestPageToPosition(pos)?.let { it.prevKey?.plus(1) ?: it.nextKey?.minus(1) }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Transaction> {
        val page = params.key ?: 1
        return try {
            val r = api.transactions(page, params.loadSize)
            LoadResult.Page(r.items.map { it.toDomain() }, prevKey = if (page == 1) null else page - 1, nextKey = if (r.hasMore) page + 1 else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: HttpException) {
            LoadResult.Error(e)
        }
    }
}

internal fun TransactionDto.toDomain() = Transaction(id, description, Money(amountMinor, currency), direction == "CREDIT", Instant.ofEpochMilli(postedAt))

@HiltViewModel
class TransactionsViewModel @Inject constructor(getPaged: GetPagedTransactionsUseCase) : ViewModel() {
    // cachedIn keeps loaded pages across configuration changes.
    val transactions: Flow<PagingData<Transaction>> = getPaged().cachedIn(viewModelScope)
}

@Composable
fun TransactionsScreen(onBack: () -> Unit, viewModel: TransactionsViewModel = hiltViewModel()) {
    val items: LazyPagingItems<Transaction> = viewModel.transactions.collectAsLazyPagingItems()
    Scaffold(topBar = { NexTopBar("Transactions", onBack) }) { padding ->
        Column(Modifier.padding(padding)) {
            when (val refresh = items.loadState.refresh) {
                is LoadState.Loading -> NexLoadingState()
                is LoadState.Error -> NexErrorState("Could not load transactions", onRetry = { items.retry() })
                else -> LazyColumn(contentPadding = PaddingValues(NexSpacing.md), verticalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                        items[index]?.let { TransactionRow(it) }
                    }
                    if (items.loadState.append is LoadState.Loading) item { Text("Loading more…") }
                    if (items.loadState.append is LoadState.Error) item { Text("Tap to retry", modifier = Modifier.fillMaxWidth().padding(NexSpacing.md), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(t: Transaction) {
    NexCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t.description, style = MaterialTheme.typography.titleMedium)
            Text((if (t.isCredit) "+" else "-") + t.amount.format(), color = if (t.isCredit) com.nexbank.designlibrary.NexColors.Success else MaterialTheme.colorScheme.onSurface)
        }
    }
}
