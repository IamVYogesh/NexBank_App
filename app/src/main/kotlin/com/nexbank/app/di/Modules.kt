package com.nexbank.app.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.nexbank.app.BuildConfig
import com.nexbank.app.NexBankApp
import com.nexbank.app.core.common.CrashReporter
import com.nexbank.app.core.common.DefaultDispatcherProvider
import com.nexbank.app.core.common.DispatcherProvider
import com.nexbank.app.core.common.TimberCrashReporter
import com.nexbank.app.core.database.CardDao
import com.nexbank.app.core.database.ContactDao
import com.nexbank.app.core.database.NexDatabase
import com.nexbank.app.core.database.PaymentDao
import com.nexbank.app.core.database.StatementDao
import com.nexbank.app.core.network.AuthInterceptor
import com.nexbank.app.core.network.BankingApi
import com.nexbank.app.core.network.FakeBackendInterceptor
import com.nexbank.app.core.network.TokenAuthenticator
import com.nexbank.app.core.security.FieldCipher
import com.nexbank.app.core.security.KeystoreFieldCipher
import com.nexbank.app.core.security.SecureTokenStore
import com.nexbank.app.di.dagger2.AuditLogger
import com.nexbank.app.feature.auth.AuthRepository
import com.nexbank.app.feature.auth.FakeAuthRepository
import com.nexbank.app.feature.auth.FirebaseAuthRepository
import com.nexbank.app.feature.cards.CardRepository
import com.nexbank.app.feature.cards.CardRepositoryImpl
import com.nexbank.app.feature.contacts.ContactRepository
import com.nexbank.app.feature.contacts.ContactRepositoryImpl
import com.nexbank.app.feature.payments.PaymentRepository
import com.nexbank.app.feature.payments.PaymentRepositoryImpl
import com.nexbank.app.feature.statements.EncryptedStatementFileStore
import com.nexbank.app.feature.statements.StatementFileStore
import com.nexbank.app.feature.statements.StatementRepository
import com.nexbank.app.feature.statements.StatementRepositoryImpl
import com.nexbank.app.feature.transactions.TransactionRepository
import com.nexbank.app.feature.transactions.TransactionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** @Binds: "when someone asks for the interface, give them this implementation". */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun dispatchers(impl: DefaultDispatcherProvider): DispatcherProvider
    @Binds abstract fun crashReporter(impl: TimberCrashReporter): CrashReporter
    @Binds abstract fun fieldCipher(impl: KeystoreFieldCipher): FieldCipher
    @Binds abstract fun paymentRepo(impl: PaymentRepositoryImpl): PaymentRepository
    @Binds abstract fun transactionRepo(impl: TransactionRepositoryImpl): TransactionRepository
    @Binds abstract fun cardRepo(impl: CardRepositoryImpl): CardRepository
    @Binds abstract fun contactRepo(impl: ContactRepositoryImpl): ContactRepository
    @Binds abstract fun statementRepo(impl: StatementRepositoryImpl): StatementRepository
    @Binds abstract fun statementFiles(impl: EncryptedStatementFileStore): StatementFileStore
}

/** @Provides: for things we cannot constructor-inject (3rd-party classes, builders, conditionals). */
@Module
@InstallIn(SingletonComponent::class)
object ProvidesModule {
    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Provides @Singleton
    fun okHttp(auth: AuthInterceptor, authenticator: TokenAuthenticator, json: Json): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .authenticator(authenticator)
        if (BuildConfig.USE_FAKE_BACKEND) builder.addInterceptor(FakeBackendInterceptor(json)) // must be last
        if (BuildConfig.DEBUG) builder.addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC // never BODY in production: it leaks PII
            redactHeader("Authorization")
        })
        return builder.build()
    }

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder().baseUrl(BuildConfig.BASE_URL).client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()

    @Provides @Singleton fun bankingApi(retrofit: Retrofit): BankingApi = retrofit.create(BankingApi::class.java)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): NexDatabase =
        Room.databaseBuilder(context, NexDatabase::class.java, "nexbank.db").build() // no destructive fallback in production

    @Provides fun paymentDao(db: NexDatabase): PaymentDao = db.paymentDao()
    @Provides fun cardDao(db: NexDatabase): CardDao = db.cardDao()
    @Provides fun statementDao(db: NexDatabase): StatementDao = db.statementDao()
    @Provides fun contactDao(db: NexDatabase): ContactDao = db.contactDao()

    /** Real Firebase when google-services.json is present, otherwise the offline fake. */
    @Provides @Singleton
    fun authRepository(@ApplicationContext context: Context, tokens: SecureTokenStore, dispatchers: DispatcherProvider, crash: CrashReporter): AuthRepository =
        if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseAuthRepository(FirebaseAuth.getInstance(), tokens, dispatchers, crash)
        else FakeAuthRepository(tokens)

    /** Bridge: expose the plain-Dagger component's object to the Hilt graph. */
    @Provides fun auditLogger(@ApplicationContext context: Context): AuditLogger = (context as NexBankApp).auditLogger
}
