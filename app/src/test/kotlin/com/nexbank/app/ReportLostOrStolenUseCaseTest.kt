package com.nexbank.app

import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.config.Country
import com.nexbank.app.core.config.CountryConfig
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.core.config.LostCardReason
import com.nexbank.app.feature.cards.CardRepository
import com.nexbank.app.feature.cards.ReportLostOrStolenUseCase
import com.nexbank.app.feature.cards.ReportOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLostOrStolenUseCaseTest {
    private val repo = mockk<CardRepository>()
    private val provider = mockk<CountryConfigProvider>()

    private fun useCase(country: Country): ReportLostOrStolenUseCase {
        every { provider.current } returns CountryConfig.forCountry(country)
        return ReportLostOrStolenUseCase(repo, provider)
    }

    @Test fun `Mexico requires the declaration`() = runTest {
        val r = useCase(Country.MEXICO)("c1", LostCardReason.LOST, declarationAccepted = false)
        assertTrue((r as AppResult.Failure).error is AppError.Validation)
        coVerify(exactly = 0) { repo.reportLostOrStolen(any(), any(), any()) }
    }

    @Test fun `Mexico with declaration calls the repository`() = runTest {
        coEvery { repo.reportLostOrStolen("c1", LostCardReason.STOLEN, true) } returns AppResult.Success(ReportOutcome("c1", "3 days"))
        val r = useCase(Country.MEXICO)("c1", LostCardReason.STOLEN, declarationAccepted = true)
        assertEquals("3 days", (r as AppResult.Success).data.replacementEta)
    }

    @Test fun `UK has no lost and stolen feature`() = runTest {
        val r = useCase(Country.UK)("c1", LostCardReason.LOST, true)
        assertTrue(r is AppResult.Failure)
    }
}
