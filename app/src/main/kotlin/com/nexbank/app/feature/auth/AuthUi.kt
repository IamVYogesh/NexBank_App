package com.nexbank.app.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.nexbank.app.BuildConfig
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.config.Country
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.designlibrary.NexPrimaryButton
import com.nexbank.designlibrary.NexSecondaryButton
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val phone: String = "",
    val otp: String = "",
    val verificationId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val countryProvider: CountryConfigProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    val country = countryProvider.config

    fun selectCountry(c: Country) = countryProvider.select(c)
    fun onPhone(v: String) = _state.update { it.copy(phone = v, error = null) }
    fun onOtp(v: String) = _state.update { it.copy(otp = v, error = null) }

    fun onGoogleToken(token: String?) {
        if (token == null) { _state.update { it.copy(error = "Google sign-in was cancelled") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val r = auth.signInWithGoogleIdToken(token)
            _state.update { it.copy(isLoading = false, error = (r as? AppResult.Failure)?.error?.userMessage) }
        }
    }

    fun sendCode(activity: Activity) {
        val phone = _state.value.phone.trim()
        if (!country.value.phoneRegex.matches(phone)) { _state.update { it.copy(error = "Enter a valid phone number") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            auth.startPhoneVerification(activity, toE164(phone)).collect { e ->
                when (e) {
                    is PhoneAuthEvent.CodeSent -> _state.update { it.copy(isLoading = false, verificationId = e.verificationId) }
                    is PhoneAuthEvent.SignedIn -> _state.update { it.copy(isLoading = false) }
                    is PhoneAuthEvent.Failed -> _state.update { it.copy(isLoading = false, error = e.message) }
                }
            }
        }
    }

    fun verify() {
        val id = _state.value.verificationId ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val r = auth.verifyOtp(id, _state.value.otp)
            _state.update { it.copy(isLoading = false, error = (r as? AppResult.Failure)?.error?.userMessage) }
        }
    }

    private fun toE164(phone: String): String = when {
        phone.startsWith("+") -> phone
        country.value.country == Country.UK -> "+44" + phone.removePrefix("0")
        country.value.country == Country.MEXICO -> "+52$phone"
        else -> "+91$phone"
    }
}

/** Google OAuth 2.0 via Credential Manager. Returns null if the user cancels. */
suspend fun requestGoogleIdToken(activity: Activity): String? {
    if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return "fake-google-token" // offline demo mode
    return try {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.country.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NexSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(NexSpacing.md),
    ) {
        Text("NexBank", style = MaterialTheme.typography.titleLarge)
        Text("Choose your market", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
            Country.entries.forEach { c ->
                FilterChip(selected = c == config.country, onClick = { viewModel.selectCountry(c) }, label = { Text(c.displayName) })
            }
        }
        if (state.verificationId == null) {
            NexTextField(state.phone, viewModel::onPhone, "Mobile number", keyboardType = KeyboardType.Phone, error = state.error)
            NexPrimaryButton("Send code", onClick = { activity?.let(viewModel::sendCode) }, loading = state.isLoading)
        } else {
            NexTextField(state.otp, viewModel::onOtp, "6-digit code", keyboardType = KeyboardType.NumberPassword, error = state.error)
            NexPrimaryButton("Verify", onClick = viewModel::verify, loading = state.isLoading)
        }
        NexSecondaryButton("Continue with Google", onClick = {
            activity?.let { a -> scope.launch { viewModel.onGoogleToken(requestGoogleIdToken(a)) } }
        })
        Text("Demo mode: any phone number works, the OTP is 123456.", style = MaterialTheme.typography.labelMedium)
    }
}
