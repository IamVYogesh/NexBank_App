package com.nexbank.app.core.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Calendar

/**
 * In-process mock server so the app runs without any backend. It short-circuits the chain, so it
 * must be the LAST application interceptor. Disable with `-PuseFakeBackend=false`.
 */
class FakeBackendInterceptor(private val json: Json) : Interceptor {
    private var contact = ContactDto("yogesh@example.com", "07123456789", "1 Demo Street, London", "SW1A 1AA")
    private val cardStatus = mutableMapOf("c1" to "ACTIVE", "c2" to "ACTIVE", "c3" to "BLOCKED")

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Authorization") == null) return error(req, 401)
        Thread.sleep(300) // simulate latency
        val path = req.url.encodedPath
        val currency = when (req.header("X-Country")) { "MX" -> "MXN"; "IN" -> "INR"; else -> "GBP" }
        val cardReport = Regex("^/v1/cards/(\\w+)/report$").matchEntire(path)
        val download = Regex("^/v1/statements/([\\w-]+)/download$").matchEntire(path)
        return when {
            path == "/v1/payments" -> ok(req, json.encodeToString(payments(currency)))
            path == "/v1/transactions" -> ok(req, json.encodeToString(transactions(req, currency)))
            path == "/v1/cards" && req.method == "GET" -> ok(req, json.encodeToString(cards()))
            cardReport != null -> {
                val id = cardReport.groupValues[1]
                cardStatus[id] = "REPLACEMENT_ORDERED"
                ok(req, json.encodeToString(ReportCardResponse(id, "REPLACEMENT_ORDERED", "3-5 working days")))
            }
            path == "/v1/profile/contact" && req.method == "GET" -> ok(req, json.encodeToString(contact))
            path == "/v1/profile/contact" && req.method == "PUT" -> {
                contact = json.decodeFromString(ContactDto.serializer(), req.bodyText())
                ok(req, json.encodeToString(contact))
            }
            path == "/v1/statements" -> ok(req, json.encodeToString(statements()))
            download != null -> respond(req, 200, pdfBytes(download.groupValues[1]).let { it.toResponseBody("application/pdf".toMediaType()) })
            else -> error(req, 404)
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun day(n: Int) = now() - n * 86_400_000L

    private fun payments(c: String) = listOf(
        PaymentDto("p1", "ACME Utilities", 12_950, c, "DELIVERED", day(6), day(5)),
        PaymentDto("p2", "Rent - J. Smith", 95_000, c, "PROCESSING", day(1), now() + 86_400_000L),
        PaymentDto("p3", "Gym Membership", 3_999, c, "SUBMITTED", day(0), now() + 2 * 86_400_000L),
        PaymentDto("p4", "Unknown Merchant", 25_000, c, "FAILED", day(3)),
        PaymentDto("p5", "Water Board", 4_120, c, "DELIVERED", day(10), day(9)),
    )

    private fun transactions(req: Request, c: String): TransactionPageDto {
        val page = req.url.queryParameter("page")!!.toInt()
        val size = req.url.queryParameter("size")!!.toInt()
        val total = 95
        val from = (page - 1) * size
        val items = (from until minOf(from + size, total)).map {
            TransactionDto("t$it", if (it % 3 == 0) "Salary" else "Card purchase #$it", (it + 1) * 317L, c, day(it), if (it % 3 == 0) "CREDIT" else "DEBIT")
        }
        return TransactionPageDto(items, page, hasMore = from + size < total)
    }

    private fun cards() = listOf(
        CardDto("c1", "4421", "VISA", cardStatus.getValue("c1")),
        CardDto("c2", "9078", "MASTERCARD", cardStatus.getValue("c2")),
        CardDto("c3", "1180", "VISA", cardStatus.getValue("c3")),
    )

    private fun statements(): List<StatementDto> {
        val cal = Calendar.getInstance()
        return (0 until 14).map {
            val y = cal.get(Calendar.YEAR); val m = cal.get(Calendar.MONTH) + 1
            cal.add(Calendar.MONTH, -1)
            StatementDto("s-$y-$m", y, m, 2_000_000L)
        }
    }

    /** A tiny valid-enough PDF padded to ~2 MB (comments) so the download shows real progress. */
    private fun pdfBytes(id: String): ByteArray {
        val head = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n" +
            "4 0 obj<</Length 47>>stream\nBT /F1 18 Tf 20 70 Td (NexBank $id) Tj ET\nendstream endobj\n" +
            "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
        val pad = "% padding padding padding padding padding padding padding padding\n".repeat(31_000)
        return (head + pad + "trailer<</Root 1 0 R>>\n%%EOF\n").toByteArray()
    }

    private fun Request.bodyText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

    private fun ok(req: Request, body: String) = respond(req, 200, body.toResponseBody("application/json".toMediaType()))
    private fun error(req: Request, code: Int) = respond(req, code, "{}".toResponseBody("application/json".toMediaType()))
    private fun respond(req: Request, code: Int, body: ResponseBody) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Error").body(body).build()
}
