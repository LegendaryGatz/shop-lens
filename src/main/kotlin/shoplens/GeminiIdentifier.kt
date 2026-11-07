package shoplens

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Gemini via the REST generateContent API.
 *
 * - identify: one multimodal call with a JSON Schema (structured output).
 * - verify:   text-only call grounded with Google Search. Grounding is not
 *             available on the Gemini API free tier; it fails cleanly there.
 */
class GeminiIdentifier(
    val model: String = Config.get("SHOPLENS_GEMINI_MODEL") ?: DEFAULT_MODEL,
    private val verifyModel: String = Config.get("SHOPLENS_GEMINI_VERIFY_MODEL") ?: model,
    private val thinking: String = Config.get("SHOPLENS_EFFORT") ?: "low",
    private val apiKey: String = Config.get("GEMINI_API_KEY") ?: error("GEMINI_API_KEY is not set"),
    /** Faster backup raced against the primary when it's slow; "none" disables hedging. */
    private val hedgeModel: String? = (Config.get("SHOPLENS_HEDGE_MODEL") ?: "gemini-3.5-flash").takeIf { it != "none" },
    private val hedgeAfterMs: Long = Config.get("SHOPLENS_HEDGE_AFTER_MS")?.toLongOrNull() ?: 8000,
) : ItemIdentifier {

    /** A copy of this identifier that uses a different (e.g. stronger) model. */
    fun withModel(m: String) = GeminiIdentifier(m, m, thinking, apiKey, hedgeModel = null)

    override suspend fun identify(images: List<PreparedImage>, userNotes: String?): Pair<Identification, Usage> {
        val parts = buildJsonArray {
            images.forEachIndexed { i, img ->
                add(buildJsonObject { put("text", "Photo ${i + 1}:") })
                add(buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "image/jpeg")
                        put("data", img.base64Jpeg)
                    })
                })
            }
            add(buildJsonObject {
                put("text", buildString {
                    append("Identify this item. All photos show the same item.")
                    if (!userNotes.isNullOrBlank()) append("\nOwner's notes (may be wrong): ").append(userNotes.take(1000))
                })
            })
        }
        suspend fun run(m: String): Pair<Identification, Usage> {
            val resp = generate(m, Prompts.IDENTIFY_SYSTEM, parts, IDENTIFICATION_SCHEMA, search = false)
            return Http.json.decodeFromString<Identification>(answerText(resp)) to usageOf(m, resp, searches = 0)
        }
        if (hedgeModel == null || hedgeModel == model) return run(model)
        return hedged(hedgeAfterMs, { run(model) }, { run(hedgeModel) })
    }

    /**
     * Gemini latency is spiky (we measured 2s and 92s for the same request), mostly
     * from queueing. If the primary model hasn't answered after [afterMs], race a
     * request to a faster backup model and take whichever succeeds first.
     */
    private suspend fun <T> hedged(afterMs: Long, primary: suspend () -> T, backup: suspend () -> T): T = coroutineScope {
        val first = async { runCatching { primary() } }
        val early = withTimeoutOrNull(afterMs) { first.await() }
        if (early != null) {
            // Answered in time, or failed fast (e.g. 503 "high demand"): then use the backup.
            return@coroutineScope early.getOrElse { primaryError ->
                runCatching { backup() }.getOrElse { throw primaryError }
            }
        }

        val second = async { runCatching { backup() } }
        val winner = select { first.onAwait { first to it }; second.onAwait { second to it } }
        val result = if (winner.second.isSuccess) winner.second
        else (if (winner.first === first) second else first).await() // first finisher failed: wait for the other
        first.cancel(); second.cancel()
        result.getOrThrow()
    }

    override suspend fun verify(id: Identification, webMatches: List<WebMatch>): Pair<Verification, Usage> {
        val prompt = buildString {
            appendLine("Initial identification from photos (JSON):")
            appendLine(Http.json.encodeToString(Identification.serializer(), id))
            if (webMatches.isNotEmpty()) {
                appendLine("\nReverse-image search found these pages showing visually matching images:")
                webMatches.take(10).forEach { appendLine("- ${it.title} — ${it.url}") }
            }
            appendLine("\nVerify the exact product name and collection/season, and find resale comparables.")
            appendLine("Prefer these sites: ${Prompts.RESALE_DOMAINS.joinToString()}.")
        }
        val parts = buildJsonArray { add(buildJsonObject { put("text", prompt) }) }
        val resp = generate(verifyModel, Prompts.VERIFY_SYSTEM, parts, VERIFICATION_SCHEMA, search = true)
        val searches = groundingOf(resp)["webSearchQueries"]?.let { (it as? JsonArray)?.size } ?: 0
        return Http.json.decodeFromString<Verification>(answerText(resp)) to usageOf(verifyModel, resp, searches)
    }

    private suspend fun generate(
        model: String,
        system: String,
        parts: JsonArray,
        schema: Map<String, Any>,
        search: Boolean,
    ): JsonObject {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
            put("contents", buildJsonArray { add(buildJsonObject { put("role", "user"); put("parts", parts) }) })
            if (search) put("tools", buildJsonArray { add(buildJsonObject { put("google_search", buildJsonObject {}) }) })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema.toJsonElement())
                put("thinkingConfig", thinkingConfig(model))
            })
        }
        try {
            return Http.postJson(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                body,
                mapOf("x-goog-api-key" to apiKey),
            )
        } catch (e: Http.ApiError) {
            val hint = when (e.status) {
                429 -> if (e.message.orEmpty().contains("quota", true))
                    " (Gemini API quota: this model or feature may need billing enabled at aistudio.google.com)"
                else " (rate limited)"
                503 -> " (model overloaded, try again or set SHOPLENS_GEMINI_MODEL)"
                else -> ""
            }
            throw IdentificationException("Gemini $model: ${e.message}$hint")
        }
    }

    /** Gemini 3+ takes a thinking level; 2.x models take a token budget instead. */
    private fun thinkingConfig(model: String) = buildJsonObject {
        if (model.startsWith("gemini-2")) put("thinkingBudget", if (thinking == "low") 512 else -1)
        else put("thinkingLevel", thinking)
    }

    private fun candidate(resp: JsonObject): JsonObject {
        val c = resp["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        if (c == null) {
            val reason = resp["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
            throw IdentificationException("Gemini returned no answer" + (reason?.let { " (blocked: $it)" } ?: ""))
        }
        when (val finish = c["finishReason"]?.jsonPrimitive?.content) {
            null, "STOP" -> {}
            "MAX_TOKENS" -> throw IdentificationException("Gemini response was cut off (MAX_TOKENS).")
            else -> throw IdentificationException("Gemini stopped early: $finish")
        }
        return c
    }

    private fun answerText(resp: JsonObject): String {
        val parts = candidate(resp)["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        val text = parts.map { it.jsonObject }
            .filter { (it["thought"] as? JsonPrimitive)?.content != "true" }
            .mapNotNull { it["text"]?.jsonPrimitive?.content }
            .joinToString("")
        if (text.isBlank()) throw IdentificationException("Gemini returned an empty answer.")
        return text
    }

    private fun groundingOf(resp: JsonObject): JsonObject =
        candidate(resp)["groundingMetadata"] as? JsonObject ?: JsonObject(emptyMap())

    private fun usageOf(model: String, resp: JsonObject, searches: Int): Usage {
        val u = resp["usageMetadata"]?.jsonObject
        fun n(k: String) = u?.get(k)?.jsonPrimitive?.longOrNull ?: 0L
        val input = n("promptTokenCount")
        val output = n("candidatesTokenCount") + n("thoughtsTokenCount") // thinking is billed as output
        val (inRate, outRate) = PRICES[model] ?: (0.0 to 0.0)
        val cost = (input * inRate + output * outRate) / 1_000_000.0 + searches * SEARCH_PRICE
        return Usage(model, input, output, cost)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        /** USD per 1M tokens (input, output), paid tier, as researched 2026-11. Free tier costs $0. */
        val PRICES = mapOf(
            "gemini-3.8-flash" to (0.75 to 3.75),
            "gemini-4-argon" to (2.0 to 10.0),
            "gemini-2.5-flash" to (0.30 to 2.50),
        )
        /** Grounding with Google Search, per query (approximate; check Google's pricing page). */
        const val SEARCH_PRICE = 0.014

        fun fromEnvOrNull(): GeminiIdentifier? = Config.get("GEMINI_API_KEY")?.let { GeminiIdentifier(apiKey = it) }
    }
}
