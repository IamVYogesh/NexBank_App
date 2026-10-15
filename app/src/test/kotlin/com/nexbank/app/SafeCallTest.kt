package com.nexbank.app

import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.safeCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class SafeCallTest {
    private val dispatcher = StandardTestDispatcher()

    @Test fun `success is wrapped`() = runTest(dispatcher) {
        assertEquals(AppResult.Success(42), safeCall(dispatcher) { 42 })
    }

    @Test fun `IOException becomes Offline`() = runTest(dispatcher) {
        val r = safeCall(dispatcher) { throw IOException("boom") }
        assertEquals(AppResult.Failure(AppError.Offline), r)
    }

    @Test fun `401 becomes Unauthorized and 500 becomes Http`() = runTest(dispatcher) {
        fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))
        assertEquals(AppResult.Failure(AppError.Unauthorized), safeCall(dispatcher) { throw http(401) })
        assertEquals(AppResult.Failure(AppError.Http(500)), safeCall(dispatcher) { throw http(500) })
    }

    /** Regression test for PROD_BUGS #1: cancellation must propagate, never be converted into a Failure. */
    @Test fun `CancellationException is rethrown`() = runTest(dispatcher) {
        try {
            safeCall(dispatcher) { throw CancellationException("cancelled") }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertTrue(true)
        }
    }
}
