package com.nexbank.app.feature.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexbank.app.BuildConfig
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.config.Country
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.core.config.Feature
import com.nexbank.app.feature.auth.AuthRepository
import com.nexbank.app.feature.support.HelpCenterActivity
import com.nexbank.designlibrary.NexCard
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val countryProvider: CountryConfigProvider,
    private val crash: CrashReporter,
) : ViewModel() {
    val config = countryProvider.config
    fun selectCountry(c: Country) = countryProvider.select(c)
    fun signOut() { viewModelScope.launch { auth.signOut() } }
    fun recentErrors() = crash.recentErrors()
}

data class Tile(val title: String, val subtitle: String, val feature: Feature, val onClick: () -> Unit)

@Composable
fun HomeScreen(
    onPayments: () -> Unit, onTransactions: () -> Unit, onCards: () -> Unit, onContacts: () -> Unit, onStatements: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDiagnostics by remember { mutableStateOf(false) }

    // Config-driven home: a tile only appears if the market enables that feature.
    val tiles = listOf(
        Tile("Payment Tracker", "Follow your payments", Feature.PAYMENT_TRACKER, onPayments),
        Tile("Transactions", "Browse your history", Feature.TRANSACTION_HISTORY, onTransactions),
        Tile("My Cards", "Block a lost or stolen card", Feature.LOST_AND_STOLEN, onCards),
        Tile("Contact details", "Email, phone, address", Feature.CONTACT_DETAILS, onContacts),
        Tile("E-Statements", "Download your statements", Feature.E_STATEMENT, onStatements),
        Tile("Help Center", "Legacy XML + Data Binding module", Feature.HELP_CENTER) { context.startActivity(Intent(context, HelpCenterActivity::class.java)) },
    ).filter { config.isEnabled(it.feature) }

    Scaffold(topBar = { NexTopBar("NexBank - ${config.country.displayName}", actions = { TextButton(onClick = viewModel::signOut) { Text("Sign out") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(NexSpacing.md), verticalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
                    Country.entries.forEach { c -> FilterChip(selected = c == config.country, onClick = { viewModel.selectCountry(c) }, label = { Text(c.code) }) }
                }
            }
            items(tiles, key = { it.title }) { tile ->
                NexCard(onClick = tile.onClick) {
                    Text(tile.title, style = MaterialTheme.typography.titleMedium)
                    Text(tile.subtitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { TextButton(onClick = { showDiagnostics = true }) { Text("Diagnostics (ROTA)") } }
        }
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } },
            title = { Text("Diagnostics") },
            text = {
                Column {
                    Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    Text("Market: ${config.country.name}, fake backend: ${BuildConfig.USE_FAKE_BACKEND}")
                    val errors = viewModel.recentErrors()
                    Text(if (errors.isEmpty()) "No recent errors" else errors.take(5).joinToString("\n"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }
}
