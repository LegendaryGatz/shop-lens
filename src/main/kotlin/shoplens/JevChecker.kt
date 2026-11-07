package shoplens

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class JevCheck(
    /** P(item is made by the claimed brand); null when no brand was claimed. */
    val brandMatch: Double?,
    /** P(item is the claimed model/line); null when no model was claimed. */
    val modelMatch: Double?,
    val category: String,
    val categoryConfidence: Double,
    val categoryAgrees: Boolean,
    /** P(a legible label, tag, serial, date code or hallmark is visible). */
    val labelVisible: Double,
    /** Expected photo-quality level, 0 (unusable) to 2 (clear). */
    val photoQuality: Double,
    /** Policy outcome computed in code: agrees | uncertain | disagrees. */
    val verdict: String,
    val reasons: List<String>,
    val usage: Usage,
)

/**
 * A second, independent opinion from TypeSafe's Jev (a System One model).
 *
 * Jev doesn't write descriptions. It returns calibrated probabilities for narrow
 * questions, which suits checking another model's claims cheaply ($0.042 per 1M
 * input tokens, output free). Image input is a TypeSafe "Dreamer-ready" (beta)
 * feature, enabled per request with a header.
 */
class JevChecker(
    private val apiKey: String,
    private val model: String = Config.get("SHOPLENS_JEV_MODEL") ?: "jev-latest",
) {
    suspend fun check(images: List<PreparedImage>, id: Identification): JevCheck {
        // A fresh UUID per image so references can't collide with ordinary text.
        val refs = images.map { "img-" + UUID.randomUUID() }

        val claim = buildJsonObject {
            put("summary", id.summary)
            put("brand", id.brand)
            put("model", id.modelOrLine)
            put("category", id.category)
        }

        val questions = buildJsonObject {
            if (id.brand != null) put("brand_match", buildJsonObject {
                put("type", "noul")
                put("instructions", "Is the item shown in `photos` made by the brand named in `claim.brand`? " +
                    "Judge from logos, labels, monograms, hardware, construction and the brand's design signatures. " +
                    "All photos show the same item.")
            })
            if (id.brand != null && id.modelOrLine != null) put("model_match", buildJsonObject {
                put("type", "noul")
                put("instructions", "Is the item shown in `photos` the specific model or line named in `claim.model` " +
                    "from the brand in `claim.brand`? All photos show the same item.")
            })
            put("category", buildJsonObject {
                put("type", "choice")
                put("instructions", "What kind of item is shown in `photos`? All photos show the same item.")
                put("criteria", CATEGORY_CRITERIA.toJsonElement())
            })
            put("label_visible", buildJsonObject {
                put("type", "noul")
                put("instructions", "Does at least one of `photos` show a legible brand label, care or size tag, " +
                    "serial number, date code, hallmark, or engraved caseback?")
            })
            put("photo_quality", buildJsonObject {
                put("type", "score")
                put("instructions", "How well do `photos` show the item for identifying and pricing it?")
                // Score levels are an ordered list; index 0 is the lowest level.
                put("criteria", buildJsonArray {
                    add(JsonPrimitive("Unusable: blurry, dark, heavily cropped, or the item is hard to make out."))
                    add(JsonPrimitive("Usable: the item is recognizable but details such as labels, hardware or texture are unclear."))
                    add(JsonPrimitive("Clear: the item and its identifying details are sharp and well lit."))
                })
            })
        }

        val body = buildJsonObject {
            put("model", model)
            put("state", buildJsonObject {
                put("photos", buildJsonArray { refs.forEach { add(JsonPrimitive(it)) } })
                put("claim", claim)
            })
            put("assets", buildJsonObject {
                images.forEachIndexed { i, img ->
                    put(refs[i], buildJsonObject {
                        put("type", "image"); put("format", "jpg"); put("data_base64", img.base64Jpeg)
                    })
                }
            })
            put("questions", questions)
        }

        val resp = Http.postJson(
            "https://api.typesafe.ai/v1/systemone",
            body,
            mapOf("Authorization" to "Bearer $apiKey", "TypeSafe-Danger-Zone-Images" to "true"),
        )
        return interpret(resp, id)
    }

    internal fun interpret(resp: JsonObject, id: Identification): JevCheck {
        val answers = resp["answers"]?.jsonObject ?: error("Jev response has no answers")
        fun ans(k: String) = answers[k]?.jsonObject
        fun num(k: String, field: String) = ans(k)?.get(field)?.jsonPrimitive?.doubleOrNull

        val brand = num("brand_match", "noul")
        val model = num("model_match", "noul")
        val cat = ans("category")?.get("choice")?.jsonPrimitive?.content ?: "other"
        val catConf = num("category", "confidence") ?: 0.0
        val label = num("label_visible", "noul") ?: 0.0
        val quality = num("photo_quality", "score") ?: 0.0
        val catAgrees = cat == id.category

        // Policy lives in code so thresholds can be tuned against real outcomes.
        val reasons = mutableListOf<String>()
        var verdict = "agrees"
        fun flag(level: String, why: String) {
            reasons += why
            if (level == "disagrees" || verdict == "agrees") verdict = level
        }
        if (brand != null && brand < 0.3) flag("disagrees", "Second opinion doubts the brand (${pct(brand)}).")
        else if (brand != null && brand < 0.6) flag("uncertain", "Second opinion is unsure of the brand (${pct(brand)}).")
        if (model != null && model < 0.4) flag("uncertain", "Second opinion is unsure of the exact model (${pct(model)}).")
        if (!catAgrees && catConf > 0.7) flag("disagrees", "Second opinion sees a different category: $cat.")
        if (quality < 0.8) flag("uncertain", "Photos are too unclear for a confident ID; retake in better light.")

        val u = resp["usage"]?.jsonObject
        val inTok = u?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val outTok = u?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        return JevCheck(
            brandMatch = brand, modelMatch = model, category = cat, categoryConfidence = catConf,
            categoryAgrees = catAgrees, labelVisible = label, photoQuality = quality,
            verdict = verdict, reasons = reasons,
            usage = Usage(model = this.model, inputTokens = inTok, outputTokens = outTok, estCostUsd = inTok * 0.042 / 1_000_000.0),
        )
    }

    private fun pct(p: Double) = "${(p * 100).toInt()}%"

    companion object {
        val CATEGORY_CRITERIA = mapOf(
            "clothing" to "Garments: tops, dresses, skirts, pants, outerwear, knitwear, suits.",
            "shoes" to "Footwear of any kind.",
            "handbags" to "Handbags, shoulder bags, totes, clutches, backpacks, luggage.",
            "accessories" to "Wallets, belts, scarves, sunglasses, hats, small leather goods, ties.",
            "jewelry" to "Rings, necklaces, bracelets, earrings, brooches.",
            "watches" to "Wristwatches and pocket watches.",
            "home" to "Home decor, tableware, furniture.",
            "art" to "Paintings, prints, sculpture, photography.",
            "other" to "None of the above.",
        )

        fun fromEnvOrNull(): JevChecker? = Config.get("TYPESAFE_API_KEY")?.let { JevChecker(it) }
    }
}
