package com.nexbank.app

import app.cash.turbine.test
import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.Money
import com.nexbank.app.feature.payments.ObservePaymentsUseCase
import com.nexbank.app.feature.payments.Payment
import com.nexbank.app.feature.payments.PaymentRepository
import com.nexbank.app.feature.payments.PaymentStatus
import com.nexbank.app.feature.payments.PaymentTrackerViewModel
import com.nexbank.app.feature.payments.RefreshPaymentsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class PaymentTrackerViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private class FakeRepo(var refreshResult: AppResult<Unit> = AppResult.Success(Unit)) : PaymentRepository {
        val payments = MutableStateFlow<List<Payment>>(emptyList())
        override fun observePayments() = payments
        override suspend fun refresh() = refreshResult
    }

    private val payment = Payment("p1", "ACME", Money(1000, "GBP"), PaymentStatus.DELIVERED, Instant.EPOCH, null)

    @Test fun `exposes payments from the repository and stops refreshing`() = runTest {
        val repo = FakeRepo().also { it.payments.value = listOf(payment) }
        val vm = PaymentTrackerViewModel(ObservePaymentsUseCase(repo), RefreshPaymentsUseCase(repo))
        vm.uiState.test {
            var s = awaitItem()
            while (s.isRefreshing || s.payments.isEmpty()) s = awaitItem()
            assertEquals(listOf(payment), s.payments)
            assertFalse(s.isRefreshing)
        }
    }

    @Test fun `refresh failure surfaces a user-facing message`() = runTest {
        val repo = FakeRepo(AppResult.Failure(AppError.Offline))
        val vm = PaymentTrackerViewModel(ObservePaymentsUseCase(repo), RefreshPaymentsUseCase(repo))
        vm.uiState.test {
            var s = awaitItem()
            while (s.error == null) s = awaitItem()
            assertEquals(AppError.Offline.userMessage, s.error)
        }
    }
}
