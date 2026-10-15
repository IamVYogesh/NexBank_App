package com.nexbank.app.feature.auth

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.common.safeCall
import com.nexbank.app.core.security.SecureTokenStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

data class Session(val userId: String, val displayName: String?)

sealed interface PhoneAuthEvent {
    data class CodeSent(val verificationId: String) : PhoneAuthEvent
    data class SignedIn(val session: Session) : PhoneAuthEvent
    data class Failed(val message: String) : PhoneAuthEvent
}

interface AuthRepository {
    val session: StateFlow<Session?>
    suspend fun signInWithGoogleIdToken(idToken: String): AppResult<Session>
    fun startPhoneVerification(activity: Activity, e164Phone: String): Flow<PhoneAuthEvent>
    suspend fun verifyOtp(verificationId: String, code: String): AppResult<Session>
    suspend fun refreshToken(): String?
    suspend fun signOut()
}

/** Real implementation: Firebase Authentication (Google OAuth 2.0 + Phone OTP). */
class FirebaseAuthRepository(
    private val auth: FirebaseAuth,
    private val tokens: SecureTokenStore,
    private val dispatchers: DispatcherProvider,
    private val crash: CrashReporter,
) : AuthRepository {
    private val _session = MutableStateFlow(auth.currentUser?.toSession())
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override suspend fun signInWithGoogleIdToken(idToken: String): AppResult<Session> = safeCall(dispatchers.io, crash) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        completeSignIn(auth.signInWithCredential(credential).await().user ?: error("Firebase returned no user"))
    }

    override fun startPhoneVerification(activity: Activity, e164Phone: String): Flow<PhoneAuthEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Instant verification / auto-retrieval of the SMS.
                auth.signInWithCredential(credential)
                    .addOnSuccessListener { result ->
                        val user = result.user ?: return@addOnSuccessListener
                        user.getIdToken(false).addOnSuccessListener {
                            tokens.save(it.token.orEmpty(), user.uid)
                            val s = user.toSession().also { s -> _session.value = s }
                            trySend(PhoneAuthEvent.SignedIn(s))
                        }
                    }
                    .addOnFailureListener { trySend(PhoneAuthEvent.Failed(it.message ?: "Sign-in failed")) }
            }
            override fun onVerificationFailed(e: FirebaseException) { trySend(PhoneAuthEvent.Failed(e.message ?: "Verification failed")) }
            override fun onCodeSent(verificationId: String, token: ForceResendingToken) { trySend(PhoneAuthEvent.CodeSent(verificationId)) }
        }
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(e164Phone).setTimeout(60L, TimeUnit.SECONDS).setActivity(activity).setCallbacks(callbacks).build()
        PhoneAuthProvider.verifyPhoneNumber(options)
        awaitClose()
    }

    override suspend fun verifyOtp(verificationId: String, code: String): AppResult<Session> = safeCall(dispatchers.io, crash) {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        completeSignIn(auth.signInWithCredential(credential).await().user ?: error("Firebase returned no user"))
    }

    override suspend fun refreshToken(): String? =
        runCatching { auth.currentUser?.getIdToken(true)?.await()?.token }.getOrNull()?.also { tokens.save(it) }

    override suspend fun signOut() {
        auth.signOut(); tokens.clear(); _session.value = null
    }

    private suspend fun completeSignIn(user: FirebaseUser): Session {
        val token = user.getIdToken(false).await().token ?: error("No ID token")
        tokens.save(token, user.uid)
        return user.toSession().also { _session.value = it }
    }

    private fun FirebaseUser.toSession() = Session(uid, displayName)
}

/** Offline stand-in used when google-services.json is absent. OTP is always 123456. */
class FakeAuthRepository(private val tokens: SecureTokenStore) : AuthRepository {
    private val _session = MutableStateFlow(tokens.userId()?.let { Session(it, "Demo User") })
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override suspend fun signInWithGoogleIdToken(idToken: String): AppResult<Session> = signedIn("demo-google")

    override fun startPhoneVerification(activity: Activity, e164Phone: String): Flow<PhoneAuthEvent> = callbackFlow {
        trySend(PhoneAuthEvent.CodeSent("fake-verification-id")); awaitClose()
    }

    override suspend fun verifyOtp(verificationId: String, code: String): AppResult<Session> =
        if (code == "123456") signedIn("demo-phone")
        else AppResult.Failure(com.nexbank.app.core.common.AppError.Validation("Wrong code (hint: 123456)"))

    override suspend fun refreshToken(): String? = tokens.token()

    override suspend fun signOut() { tokens.clear(); _session.value = null }

    private fun signedIn(uid: String): AppResult<Session> {
        tokens.save("fake-token-$uid", uid)
        return AppResult.Success(Session(uid, "Demo User").also { _session.value = it })
    }
}
