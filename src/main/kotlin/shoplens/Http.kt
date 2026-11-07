package shoplens

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Shared JSON + HTTP helpers for the REST-based providers (Gemini, TypeSafe/Jev). */
object Http {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    class ApiError(val status: Int, message: String) : RuntimeException(message)

    suspend fun postJson(
        url: String,
        body: JsonElement,
        headers: Map<String, String>,
        timeout: Duration = Duration.ofSeconds(90),
    ): JsonObject {
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        val parsed = runCatching { json.parseToJsonElement(resp.body()) as? JsonObject }.getOrNull()
        if (resp.statusCode() !in 200..299) {
            val msg = parsed?.let { errorMessage(it) } ?: resp.body().take(300)
            throw ApiError(resp.statusCode(), msg)
        }
        return parsed ?: throw ApiError(resp.statusCode(), "Non-JSON response")
    }

    private fun errorMessage(o: JsonObject): String? {
        val err = o["error"] ?: o["detail"] ?: return null
        return when (err) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.content ?: err.toString()
            is JsonPrimitive -> err.content
            else -> err.toString()
        }.take(300)
    }
}

/** Converts plain Kotlin maps/lists/primitives (e.g. our JSON Schemas) into a JsonElement. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
