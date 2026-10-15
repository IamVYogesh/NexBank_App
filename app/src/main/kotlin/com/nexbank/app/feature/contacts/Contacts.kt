package com.nexbank.app.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexbank.app.core.common.AppError
import com.nexbank.app.core.common.AppResult
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.common.safeCall
import com.nexbank.app.core.config.CountryConfig
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.core.database.ContactDao
import com.nexbank.app.core.database.ContactEntity
import com.nexbank.app.core.network.BankingApi
import com.nexbank.app.core.network.ContactDto
import com.nexbank.app.core.security.FieldCipher
import com.nexbank.designlibrary.NexPrimaryButton
import com.nexbank.designlibrary.NexSpacing
import com.nexbank.designlibrary.NexTextField
import com.nexbank.designlibrary.NexTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------- domain ----------
data class ContactDetails(val email: String, val phone: String, val addressLine: String, val postcode: String)
data class ContactErrors(val email: String? = null, val phone: String? = null, val address: String? = null, val postcode: String? = null) {
    val isValid get() = email == null && phone == null && address == null && postcode == null
}

/** Pure function -> trivially unit-testable (see ContactValidatorTest). */
class ContactValidator @Inject constructor() {
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun validate(d: ContactDetails, cfg: CountryConfig) = ContactErrors(
        email = if (emailRegex.matches(d.email.trim())) null else "Enter a valid email address",
        phone = if (cfg.phoneRegex.matches(d.phone.trim().replace(" ", ""))) null else "Enter a valid ${cfg.country.displayName} mobile number",
        address = if (d.addressLine.isBlank()) "Address is required" else null,
        postcode = if (cfg.postcodeRegex.matches(d.postcode.trim())) null else "Enter a valid postcode",
    )
}

interface ContactRepository {
    fun observe(): Flow<ContactDetails?>
    suspend fun refresh(): AppResult<Unit>
    suspend fun update(details: ContactDetails): AppResult<Unit>
}

class UpdateContactDetailsUseCase @Inject constructor(
    private val repo: ContactRepository,
    private val validator: ContactValidator,
    private val config: CountryConfigProvider,
) {
    suspend operator fun invoke(d: ContactDetails): AppResult<Unit> {
        val errors = validator.validate(d, config.current)
        if (!errors.isValid) return AppResult.Failure(AppError.Validation("Please fix the highlighted fields"))
        return repo.update(d)
    }
}

// ---------- data: PII is encrypted BEFORE it reaches Room ----------
class ContactRepositoryImpl @Inject constructor(
    private val api: BankingApi,
    private val dao: ContactDao,
    private val cipher: FieldCipher,
    private val dispatchers: DispatcherProvider,
    private val crash: CrashReporter,
) : ContactRepository {

    override fun observe(): Flow<ContactDetails?> = dao.observe().map { entity ->
        // PROD BUG NOTE (docs/PROD_BUGS.md #3): a wiped/rotated Keystore key makes decrypt() throw.
        // We treat it as "no cached data" instead of crashing; the next refresh() repopulates the row.
        entity?.let { runCatching { it.toDomain() }.onFailure { e -> crash.record(e, "contact decrypt") }.getOrNull() }
    }

    override suspend fun refresh() = safeCall(dispatchers.io, crash) { dao.upsert(api.contact().toDomain().toEntity()) }

    override suspend fun update(details: ContactDetails) = safeCall(dispatchers.io, crash) {
        dao.upsert(api.updateContact(ContactDto(details.email, details.phone, details.addressLine, details.postcode)).toDomain().toEntity())
    }

    private fun ContactDto.toDomain() = ContactDetails(email, phone, addressLine, postcode)
    private fun ContactDetails.toEntity() = ContactEntity(
        emailEnc = cipher.encrypt(email), phoneEnc = cipher.encrypt(phone),
        addressEnc = cipher.encrypt(addressLine), postcodeEnc = cipher.encrypt(postcode), updatedAt = System.currentTimeMillis(),
    )
    private fun ContactEntity.toDomain() = ContactDetails(cipher.decrypt(emailEnc), cipher.decrypt(phoneEnc), cipher.decrypt(addressEnc), cipher.decrypt(postcodeEnc))
}

// ---------- presentation ----------
data class ContactFormState(
    val email: String = "", val phone: String = "", val address: String = "", val postcode: String = "",
    val errors: ContactErrors = ContactErrors(), val isSaving: Boolean = false, val isLoaded: Boolean = false,
)

@HiltViewModel
class ContactDetailsViewModel @Inject constructor(
    private val repo: ContactRepository,
    private val update: UpdateContactDetailsUseCase,
    private val validator: ContactValidator,
    private val config: CountryConfigProvider,
) : ViewModel() {
    private val _form = MutableStateFlow(ContactFormState())
    val form: StateFlow<ContactFormState> = _form.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { repo.refresh() }
        viewModelScope.launch {
            // Pre-fill the form once from the (decrypted) cache; later edits are not overwritten.
            repo.observe().collect { d ->
                if (d != null && !_form.value.isLoaded) _form.update { it.copy(email = d.email, phone = d.phone, address = d.addressLine, postcode = d.postcode, isLoaded = true) }
            }
        }
    }

    fun onEmail(v: String) = _form.update { it.copy(email = v) }
    fun onPhone(v: String) = _form.update { it.copy(phone = v) }
    fun onAddress(v: String) = _form.update { it.copy(address = v) }
    fun onPostcode(v: String) = _form.update { it.copy(postcode = v) }

    fun save() {
        val f = _form.value
        val details = ContactDetails(f.email, f.phone, f.address, f.postcode)
        val errors = validator.validate(details, config.current)
        _form.update { it.copy(errors = errors) }
        if (!errors.isValid) return
        viewModelScope.launch {
            _form.update { it.copy(isSaving = true) }
            val r = update(details)
            _form.update { it.copy(isSaving = false) }
            _messages.send(if (r is AppResult.Failure) r.error.userMessage else "Contact details updated")
        }
    }
}

@Composable
fun ContactDetailsScreen(onBack: () -> Unit, viewModel: ContactDetailsViewModel = hiltViewModel()) {
    val f by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(topBar = { NexTopBar("Contact details", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).padding(NexSpacing.md).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NexSpacing.sm)) {
            NexTextField(f.email, viewModel::onEmail, "Email", error = f.errors.email, keyboardType = KeyboardType.Email)
            NexTextField(f.phone, viewModel::onPhone, "Mobile number", error = f.errors.phone, keyboardType = KeyboardType.Phone)
            NexTextField(f.address, viewModel::onAddress, "Address", error = f.errors.address)
            NexTextField(f.postcode, viewModel::onPostcode, "Postcode", error = f.errors.postcode)
            NexPrimaryButton("Save", onClick = viewModel::save, loading = f.isSaving)
        }
    }
}
