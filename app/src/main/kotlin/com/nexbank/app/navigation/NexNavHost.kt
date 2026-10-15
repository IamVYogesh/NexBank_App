package com.nexbank.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexbank.app.feature.auth.AuthRepository
import com.nexbank.app.feature.auth.LoginScreen
import com.nexbank.app.feature.cards.CardsScreen
import com.nexbank.app.feature.contacts.ContactDetailsScreen
import com.nexbank.app.feature.home.HomeScreen
import com.nexbank.app.feature.payments.PaymentTrackerScreen
import com.nexbank.app.feature.statements.StatementsScreen
import com.nexbank.app.feature.transactions.TransactionsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val PAYMENTS = "payments"
    const val TRANSACTIONS = "transactions"
    const val CARDS = "cards"
    const val CONTACTS = "contacts"
    const val STATEMENTS = "statements"
}

@HiltViewModel
class SessionViewModel @Inject constructor(auth: AuthRepository) : ViewModel() {
    val session = auth.session
}

@Composable
fun NexNavHost(sessionViewModel: SessionViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val start = if (sessionViewModel.session.value != null) Routes.HOME else Routes.LOGIN

    // Single place that reacts to sign-in / sign-out (and token-expiry sign-outs).
    LaunchedEffect(session) {
        val current = nav.currentDestination?.route
        if (session == null && current != null && current != Routes.LOGIN) nav.navigate(Routes.LOGIN) { popUpTo(0) }
        if (session != null && current == Routes.LOGIN) nav.navigate(Routes.HOME) { popUpTo(0) }
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.LOGIN) { LoginScreen() }
        composable(Routes.HOME) {
            HomeScreen(
                onPayments = { nav.navigate(Routes.PAYMENTS) }, onTransactions = { nav.navigate(Routes.TRANSACTIONS) },
                onCards = { nav.navigate(Routes.CARDS) }, onContacts = { nav.navigate(Routes.CONTACTS) }, onStatements = { nav.navigate(Routes.STATEMENTS) },
            )
        }
        composable(Routes.PAYMENTS) { PaymentTrackerScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.TRANSACTIONS) { TransactionsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CARDS) { CardsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CONTACTS) { ContactDetailsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.STATEMENTS) { StatementsScreen(onBack = { nav.popBackStack() }) }
    }
}
