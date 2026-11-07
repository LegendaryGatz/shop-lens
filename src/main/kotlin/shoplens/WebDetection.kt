package shoplens

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Optional reverse-image search using Google Cloud Vision "Web Detection"
 * ($3.50 / 1k calls, first 1k/month free). Its value is `pagesWithMatchingImages`:
 * often resale/retail listings whose titles carry the exact product name.
 * Enabled only when GOOGLE_VISION_API_KEY is set.
 */
class WebDetection(private val apiKey: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(image: PreparedImage, maxResults: Int = 10): List<WebMatch> {
        val body = buildJsonObject {
            put("requests", buildJsonArray {
                add(buildJsonObject {
                    put("image", buildJsonObject { put("content", image.base64Jpeg) })
                    put("features", buildJsonArray {
                        add(buildJsonObject { put("type", "WEB_DETECTION"); put("maxResults", maxResults) })
                    })
                })
            })
        }.toString()

        val req = HttpRequest.newBuilder(URI("https://vision.googleapis.com/v1/images:annotate"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("X-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() != 200) throw RuntimeException("Vision API HTTP ${resp.statusCode()}")

        val web = json.parseToJsonElement(resp.body()).jsonObject["responses"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("webDetection")?.jsonObject ?: return emptyList()

        return web["pagesWithMatchingImages"]?.jsonArray.orEmpty().mapNotNull { el ->
            val page = el as? JsonObject ?: return@mapNotNull null
            val url = page["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = page["pageTitle"]?.jsonPrimitive?.content
                ?.replace(Regex("<[^>]+>"), "") // titles come back with <b> highlight tags
                ?: url
            WebMatch(title, url)
        }
    }

    companion object {
        fun fromEnv(): WebDetection? = Config.get("GOOGLE_VISION_API_KEY")?.takeIf { it.isNotBlank() }?.let(::WebDetection)
    }
}
