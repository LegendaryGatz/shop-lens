package shoplens

import kotlinx.serialization.Serializable

// ---- Stage 1: what the vision model returns (mirrors IDENTIFICATION_SCHEMA) ----

@Serializable
data class Confidence(val brand: Double, val model: Double, val collection: Double)

@Serializable
data class Condition(val grade: String, val notes: String)

@Serializable
data class Authenticity(
    val positiveSignals: List<String>,
    val redFlags: List<String>,
    val recommendedChecks: List<String>,
)

@Serializable
data class Alternative(val brand: String, val model: String, val reason: String)

@Serializable
data class ResaleEstimate(
    val lowUsd: Int,
    val typicalUsd: Int,
    val highUsd: Int,
    val demand: String,
    val reasoning: String,
)

@Serializable
data class Identification(
    val summary: String,
    val category: String,
    val subcategory: String?,
    val brand: String?,
    val designer: String?,
    val modelOrLine: String?,
    val collectionOrSeason: String?,
    val yearRange: String?,
    val gender: String?,
    val materials: List<String>,
    val colors: List<String>,
    val hardware: String?,
    val size: String?,
    val visibleText: List<String>,
    val identifyingFeatures: List<String>,
    val condition: Condition,
    val authenticity: Authenticity,
    val confidence: Confidence,
    val alternatives: List<Alternative>,
    val originalRetailUsd: Int?,
    val resale: ResaleEstimate,
    val photoRequests: List<String>,
    val searchQueries: List<String>,
)

// ---- Stage 2 (optional): web-verified details and comps ----

@Serializable
data class Comparable(
    val title: String,
    val url: String,
    val source: String,
    val priceUsd: Int?,
    val status: String,
)

@Serializable
data class Verification(
    val confirmedProductName: String?,
    val collectionOrSeason: String?,
    val originalRetailUsd: Int?,
    val comparables: List<Comparable>,
    val adjustedResale: ResaleEstimate?,
    val notes: String,
)

// ---- API response ----

@Serializable
data class Usage(val model: String, val inputTokens: Long, val outputTokens: Long, val estCostUsd: Double)

@Serializable
data class IdentifyResponse(
    val identification: Identification,
    val payout: PayoutEstimate,
    val verification: Verification? = null,
    val verificationError: String? = null,
    val webMatches: List<WebMatch> = emptyList(),
    val crossCheck: JevCheck? = null,
    val escalatedTo: String? = null,
    val notices: List<String> = emptyList(),
    val images: List<ImageInfo>,
    val timingsMs: Map<String, Long>,
    val usage: List<Usage>,
)

@Serializable
data class ImageInfo(val width: Int, val height: Int, val bytes: Int)

@Serializable
data class WebMatch(val title: String, val url: String)

@Serializable
data class ErrorResponse(val error: String)

// ---- JSON Schemas for structured output ----
// Structured outputs require every property listed in `required` and
// additionalProperties=false; optional values are expressed as nullable types.

private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
private fun nstr(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
private fun nint(desc: String) = mapOf("type" to listOf("integer", "null"), "description" to desc)
private fun num(desc: String) = mapOf("type" to "number", "description" to desc)
private fun strs(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)
private fun obj(vararg props: Pair<String, Any>): Map<String, Any> = mapOf(
    "type" to "object",
    "properties" to props.toMap(),
    "required" to props.map { it.first },
    "additionalProperties" to false,
)

private val RESALE = obj(
    "lowUsd" to int("Low end of a realistic pre-owned sale price in USD for this item in this condition"),
    "typicalUsd" to int("Most likely pre-owned sale price in USD"),
    "highUsd" to int("High end of a realistic pre-owned sale price in USD"),
    "demand" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high", "very_high")),
    "reasoning" to str("Short justification: comparable pieces, brand heat, condition, and seasonality"),
)

val CATEGORIES = listOf(
    "clothing", "shoes", "handbags", "accessories", "jewelry", "watches", "home", "art", "other",
)

val IDENTIFICATION_SCHEMA: Map<String, Any> = obj(
    "summary" to str("One-sentence plain-English identification, e.g. 'Gucci Horsebit 1955 shoulder bag in GG Supreme canvas, c. 2020'"),
    "category" to mapOf("type" to "string", "enum" to CATEGORIES),
    "subcategory" to nstr("e.g. 'blazer', 'women's pumps', 'men's sneakers', 'shoulder bag', 'dive watch'"),
    "brand" to nstr("House/brand name, or null if it cannot be determined"),
    "designer" to nstr("Creative director or designer responsible for this piece's era, e.g. 'Alessandro Michele'. Null if unknown."),
    "modelOrLine" to nstr("Named model, line or style, e.g. 'Speedy 30', 'Submariner Date 126610LN', 'Le Chiquito'"),
    "collectionOrSeason" to nstr("Specific collection/season such as 'FW19' or 'Cruise 2018' ONLY if evidence supports it; otherwise null"),
    "yearRange" to nstr("Estimated production years, e.g. '2016-2019'"),
    "gender" to nstr("womens, mens, unisex, or null"),
    "materials" to strs("Materials, e.g. 'lambskin', 'GG Supreme coated canvas', '904L steel'"),
    "colors" to strs("Colors / colorway names"),
    "hardware" to nstr("Hardware finish and details if relevant"),
    "size" to nstr("Size if visible on a tag or inferable"),
    "visibleText" to strs("Every legible word, number, serial, date code or label transcribed exactly as seen"),
    "identifyingFeatures" to strs("The visual cues that drove the identification"),
    "condition" to obj(
        "grade" to mapOf("type" to "string", "enum" to listOf("new_with_tags", "pristine", "excellent", "very_good", "good", "fair", "unknown")),
        "notes" to str("Visible wear, damage, or what could not be assessed from these photos"),
    ),
    "authenticity" to obj(
        "positiveSignals" to strs("Details consistent with an authentic item"),
        "redFlags" to strs("Details that look inconsistent with authentic production"),
        "recommendedChecks" to strs("Specific things to inspect or photograph to confirm authenticity"),
    ),
    "confidence" to obj(
        "brand" to num("0-1 confidence in the brand"),
        "model" to num("0-1 confidence in the model/line"),
        "collection" to num("0-1 confidence in collection/season (0 when null)"),
    ),
    "alternatives" to mapOf(
        "type" to "array",
        "description" to "Other plausible identifications, most likely first (empty if confident)",
        "items" to obj("brand" to str("Brand"), "model" to str("Model or line"), "reason" to str("Why it is plausible")),
    ),
    "originalRetailUsd" to nint("Approximate original retail price in USD, null if unknown"),
    "resale" to RESALE,
    "photoRequests" to strs("Additional photos that would most improve the identification or price (e.g. 'inside label', 'date code', 'caseback')"),
    "searchQueries" to strs("2-4 precise web search queries to find this exact item on resale sites"),
)

val VERIFICATION_SCHEMA: Map<String, Any> = obj(
    "confirmedProductName" to nstr("Exact product name as listed by a retailer or resale site, or null if not confirmed"),
    "collectionOrSeason" to nstr("Collection/season if a source states it, else null"),
    "originalRetailUsd" to nint("Original retail price in USD if a source states it"),
    "comparables" to mapOf(
        "type" to "array",
        "items" to obj(
            "title" to str("Listing title"),
            "url" to str("Listing URL exactly as returned by search"),
            "source" to str("Site name, e.g. TheRealReal"),
            "priceUsd" to nint("Listed or sold price in USD"),
            "status" to mapOf("type" to "string", "enum" to listOf("sold", "listed", "retail", "unknown")),
        ),
    ),
    "adjustedResale" to mapOf("anyOf" to listOf(RESALE, mapOf("type" to "null"))),
    "notes" to str("What the search confirmed or contradicted about the initial identification"),
)
