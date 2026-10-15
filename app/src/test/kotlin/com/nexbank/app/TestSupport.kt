package com.nexbank.app

import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Replaces Dispatchers.Main so ViewModels using viewModelScope work in plain JVM tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
    override val io = d
    override val default = d
    override val main = d
}

class NoopCrashReporter : CrashReporter {
    val recorded = mutableListOf<Throwable>()
    override fun log(message: String) = Unit
    override fun record(t: Throwable, context: String?) { recorded += t }
    override fun recentErrors() = emptyList<String>()
}
