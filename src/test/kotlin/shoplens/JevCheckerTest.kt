package shoplens

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JevCheckerTest {
    private val checker = JevChecker(apiKey = "unused")

    private fun response(brand: Double, model: Double, category: String, catConf: Double, quality: Double): JsonObject =
        Http.json.parseToJsonElement(
            """
            {"answers": {
              "brand_match": {"type": "noul", "noul": $brand},
              "model_match": {"type": "noul", "noul": $model},
              "category": {"type": "choice", "choice": "$category", "confidence": $catConf, "probabilities": {}},
              "label_visible": {"type": "noul", "noul": 0.8},
              "photo_quality": {"type": "score", "score": $quality, "confidence": 0.9, "legend": {}, "probabilities": {}}
            }, "usage": {"input_tokens": 1000000, "output_tokens": 50}}
            """,
        ).jsonObject

    @Test fun agreesWhenEverythingLinesUp() {
        val c = checker.interpret(response(0.9, 0.8, "handbags", 0.95, 1.8), sampleId())
        assertEquals("agrees", c.verdict)
        assertEquals(0.042, c.usage.estCostUsd, 1e-9) // 1M input tokens at $0.042
    }

    @Test fun disagreesOnBrand() {
        val c = checker.interpret(response(0.1, 0.8, "handbags", 0.95, 1.8), sampleId())
        assertEquals("disagrees", c.verdict)
        assertTrue(c.reasons.any { "brand" in it })
    }

    @Test fun disagreesOnConfidentCategoryMismatch() {
        val c = checker.interpret(response(0.9, 0.8, "shoes", 0.9, 1.8), sampleId())
        assertEquals("disagrees", c.verdict)
    }

    @Test fun uncertainOnBlurryPhotos() {
        val c = checker.interpret(response(0.9, 0.8, "handbags", 0.95, 0.3), sampleId())
        assertEquals("uncertain", c.verdict)
    }

    @Test fun disagreeIsNotDowngradedByLaterUncertainty() {
        val c = checker.interpret(response(0.1, 0.2, "handbags", 0.95, 0.3), sampleId())
        assertEquals("disagrees", c.verdict)
    }
}
