package com.nexbank.app

import app.cash.turbine.test
import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.network.BankingApi
import com.nexbank.app.feature.statements.DownloadState
import com.nexbank.app.feature.statements.StatementDownloader
import com.nexbank.app.feature.statements.StatementFileStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

private class InMemoryStore : StatementFileStore {
    val files = mutableMapOf<String, ByteArrayOutputStream>()
    override fun openOutput(id: String): OutputStream = ByteArrayOutputStream().also { files[id] = it }
    override fun exists(id: String) = files.containsKey(id)
    override fun delete(id: String) { files.remove(id) }
    override fun decryptForViewing(id: String): File = error("not needed")
    override fun clearViewCache() = Unit
}

class StatementDownloaderTest {
    private val api = mockk<BankingApi>()
    private val store = InMemoryStore()
    private val dispatcher = UnconfinedTestDispatcher()
    private val crash = NoopCrashReporter()
    private val downloader = StatementDownloader(api, store, TestDispatchers(dispatcher), crash)

    @Test fun `streams all bytes, reports progress up to 100 and ends with Done`() = runTest {
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        coEvery { api.downloadStatement("s1") } returns payload.toResponseBody()

        downloader.download("s1").test {
            val states = mutableListOf<DownloadState>()
            while (true) {
                val s = awaitItem(); states += s
                if (s is DownloadState.Done) break
            }
            awaitComplete()
            assertTrue(states.first() is DownloadState.Progress)
            assertEquals(100, (states.filterIsInstance<DownloadState.Progress>().last()).percent)
        }
        assertArrayEquals(payload, store.files.getValue("s1").toByteArray())
    }

    @Test fun `network failure emits Failed and removes the partial file`() = runTest {
        coEvery { api.downloadStatement("s2") } throws IOException("offline")
        downloader.download("s2").test {
            assertEquals(DownloadState.Failed(AppError.Offline), awaitItem())
            awaitComplete()
        }
        assertFalse(store.exists("s2"))
    }
}
