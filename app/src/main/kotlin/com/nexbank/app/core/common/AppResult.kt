package com.nexbank.app.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Single result type used across repositories/use cases. UI never sees raw exceptions. */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed class AppError(val userMessage: String) {
    data object Offline : AppError("You appear to be offline. Please check your connection.")
    data object Unauthorized : AppError("Your session has expired. Please sign in again.")
    data class Http(val code: Int) : AppError("Something went wrong (code $code). Please try again.")
    data class Validation(val reason: String) : AppError(reason)
    data class Storage(val reason: String) : AppError("We could not access secure storage on this device.")
    data class Unknown(val cause: Throwable?) : AppError("Unexpected error. Please try again.")
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> = also { if (it is AppResult.Success) block(it.data) }
inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> = also { if (it is AppResult.Failure) block(it.error) }

/**
 * Runs [block] on [dispatcher] and converts exceptions into [AppResult.Failure].
 *
 * PROD BUG NOTE (docs/PROD_BUGS.md #1): `catch (e: Exception)` also swallows [CancellationException],
 * which breaks structured concurrency (screens kept "loading" after navigating away). We always rethrow it.
 */
suspend fun <T> safeCall(
    dispatcher: CoroutineDispatcher,
    crash: CrashReporter? = null,
    block: suspend () -> T,
): AppResult<T> = withContext(dispatcher) {
    try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        crash?.record(e, "http ${e.code()}")
        AppResult.Failure(if (e.code() == 401) AppError.Unauthorized else AppError.Http(e.code()))
    } catch (e: IOException) {
        AppResult.Failure(AppError.Offline)
    } catch (e: SerializationException) {
        crash?.record(e, "bad payload")
        AppResult.Failure(AppError.Unknown(e))
    } catch (e: Exception) {
        crash?.record(e)
        AppResult.Failure(AppError.Unknown(e))
    }
}
