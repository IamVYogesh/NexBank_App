package com.nexbank.app.core.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** REST contract. Retrofit turns this interface into an implementation at runtime. */
interface BankingApi {
    @GET("v1/payments") suspend fun payments(): List<PaymentDto>

    @GET("v1/transactions") suspend fun transactions(@Query("page") page: Int, @Query("size") size: Int): TransactionPageDto

    @GET("v1/cards") suspend fun cards(): List<CardDto>

    @POST("v1/cards/{id}/report")
    suspend fun reportCard(@Path("id") id: String, @Body body: ReportCardRequest): ReportCardResponse

    @GET("v1/profile/contact") suspend fun contact(): ContactDto

    @PUT("v1/profile/contact") suspend fun updateContact(@Body body: ContactDto): ContactDto

    @GET("v1/statements") suspend fun statements(): List<StatementDto>

    /** @Streaming: do NOT buffer the whole PDF in memory; we read it in chunks. */
    @Streaming @GET("v1/statements/{id}/download")
    suspend fun downloadStatement(@Path("id") id: String): ResponseBody
}

@Serializable data class PaymentDto(val id: String, val payee: String, val amountMinor: Long, val currency: String, val status: String, val createdAt: Long, val expectedBy: Long? = null)
@Serializable data class TransactionDto(val id: String, val description: String, val amountMinor: Long, val currency: String, val postedAt: Long, val direction: String)
@Serializable data class TransactionPageDto(val items: List<TransactionDto>, val page: Int, val hasMore: Boolean)
@Serializable data class CardDto(val id: String, val last4: String, val network: String, val status: String)
@Serializable data class ReportCardRequest(val reason: String, val declarationAccepted: Boolean)
@Serializable data class ReportCardResponse(val cardId: String, val status: String, val replacementEta: String? = null)
@Serializable data class ContactDto(val email: String, val phone: String, val addressLine: String, val postcode: String)
@Serializable data class StatementDto(val id: String, val year: Int, val month: Int, val sizeBytes: Long)
