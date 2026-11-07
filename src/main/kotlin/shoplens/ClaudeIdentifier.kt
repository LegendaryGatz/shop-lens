package shoplens

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Provider-neutral contract, so another vision model can be swapped in later. */
interface ItemIdentifier {
    suspend fun identify(images: List<PreparedImage>, userNotes: String?): Pair<Identification, Usage>
    suspend fun verify(id: Identification, webMatches: List<WebMatch>): Pair<Verification, Usage>
}

class IdentificationException(message: String) : RuntimeException(message)

class ClaudeIdentifier(
    private val model: String = Config.get("SHOPLENS_MODEL") ?: "claude-opus-5-5",
    private val verifyModel: String = Config.get("SHOPLENS_VERIFY_MODEL") ?: model,
    effort: String = Config.get("SHOPLENS_EFFORT") ?: "low",
    private val client: AnthropicClient = Config.get("ANTHROPIC_API_KEY")
        ?.let { AnthropicOkHttpClient.builder().fromEnv().apiKey(it).build() }
        ?: AnthropicOkHttpClient.fromEnv(),
) : ItemIdentifier {

    private val effort = OutputConfig.Effort.of(effort.lowercase())
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun identify(images: List<PreparedImage>, userNotes: String?): Pair<Identification, Usage> =
        withContext(Dispatchers.IO) {
            val content = buildList {
                images.forEachIndexed { i, img ->
                    add(ContentBlockParam.ofText(TextBlockParam.builder().text("Photo ${i + 1}:").build()))
                    add(
                        ContentBlockParam.ofImage(
                            ImageBlockParam.builder().source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(img.base64Jpeg)
                                    .build(),
                            ).build(),
                        ),
                    )
                }
                val ask = buildString {
                    append("Identify this item. All photos show the same item.")
                    if (!userNotes.isNullOrBlank()) append("\nOwner's notes (may be wrong): ").append(userNotes.take(1000))
                }
                add(ContentBlockParam.ofText(TextBlockParam.builder().text(ask).build()))
            }

            val params = baseParams(model, Prompts.IDENTIFY_SYSTEM, IDENTIFICATION_SCHEMA)
                .addUserMessageOfContentBlockParams(content)
                .build()

            val msg = client.messages().create(params)
            json.decodeFromString<Identification>(finalJson(msg)) to usageOf(model, msg)
        }

    override suspend fun verify(id: Identification, webMatches: List<WebMatch>): Pair<Verification, Usage> =
        withContext(Dispatchers.IO) {
            val prompt = buildString {
                appendLine("Initial identification from photos (JSON):")
                appendLine(json.encodeToString(Identification.serializer(), id))
                if (webMatches.isNotEmpty()) {
                    appendLine("\nReverse-image search found these pages showing visually matching images:")
                    webMatches.take(10).forEach { appendLine("- ${it.title} — ${it.url}") }
                }
                appendLine("\nVerify the exact product name and collection/season, and find resale comparables.")
            }

            val builder = baseParams(verifyModel, Prompts.VERIFY_SYSTEM, VERIFICATION_SCHEMA)
                .addTool(
                    WebSearchTool20260209.builder()
                        .maxUses(4L)
                        .allowedDomains(Prompts.RESALE_DOMAINS)
                        .build(),
                )
                .addUserMessage(prompt)

            // Server-side web search can hit its iteration limit and return pause_turn;
            // resend with the partial assistant turn appended to let it finish.
            var msg = client.messages().create(builder.build())
            var continues = 0
            while (msg.stopReason().orElse(null) == StopReason.PAUSE_TURN && continues++ < 2) {
                builder.addMessage(msg)
                msg = client.messages().create(builder.build())
            }
            json.decodeFromString<Verification>(finalJson(msg)) to usageOf(verifyModel, msg)
        }

    private fun baseParams(model: String, system: String, schema: Map<String, Any>): MessageCreateParams.Builder =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            .outputConfig(
                OutputConfig.builder()
                    .effort(effort)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(
                                JsonOutputFormat.Schema.builder()
                                    .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .apply {
                // If a safety classifier declines (rare for this use), let the server
                // retry on an appropriate fallback model instead of failing outright.
                if (model in FALLBACK_MODELS) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }

    /** Returns the JSON text the model produced, or throws a readable error. */
    private fun finalJson(msg: Message): String {
        when (msg.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw IdentificationException(
                "The model declined to analyze these images" +
                    (msg.stopDetails().flatMap { it.explanation() }.map { ": $it" }.orElse(".")),
            )
            StopReason.MAX_TOKENS -> throw IdentificationException("Model response was cut off (max_tokens).")
            else -> {}
        }
        // With server tools the content interleaves tool calls/results; the
        // structured answer is the text after the last non-text block.
        val blocks = msg.content()
        val lastNonText = blocks.indexOfLast { !it.isText() }
        val text = blocks.drop(lastNonText + 1).mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw IdentificationException("Model returned no answer.")
        return text
    }

    private fun usageOf(model: String, msg: Message): Usage {
        val u = msg.usage()
        val (inRate, outRate) = PRICES[model] ?: (0.0 to 0.0)
        val cacheRead = u.cacheReadInputTokens().orElse(0L)
        val cacheWrite = u.cacheCreationInputTokens().orElse(0L)
        val cost = (u.inputTokens() * inRate + cacheWrite * inRate * 1.25 + cacheRead * inRate * 0.1 +
            u.outputTokens() * outRate) / 1_000_000.0
        return Usage(model, u.inputTokens() + cacheRead + cacheWrite, u.outputTokens(), cost)
    }

    companion object {
        /** USD per 1M tokens (input, output), first-party API, as of 2026-10. */
        val PRICES = mapOf(
            "claude-opus-5-5" to (4.0 to 20.0),
            "claude-sonnet-5-5" to (2.0 to 10.0),
            "claude-haiku-5-5" to (0.10 to 0.50),
        )
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-5")

    }
}
