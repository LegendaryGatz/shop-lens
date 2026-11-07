package shoplens

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("shoplens")

const val MAX_IMAGES = 5
const val MAX_UPLOAD_BYTES = 15L * 1024 * 1024

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

/** SHOPLENS_PROVIDER=gemini|claude; defaults to Gemini when GEMINI_API_KEY is set. */
fun defaultIdentifier(): ItemIdentifier {
    val provider = Config.get("SHOPLENS_PROVIDER")?.lowercase()
        ?: if (Config.get("GEMINI_API_KEY") != null) "gemini" else "claude"
    return if (provider == "gemini") GeminiIdentifier() else ClaudeIdentifier()
}

/** SHOPLENS_ESCALATION_MODEL, e.g. gemini-4-argon (needs a paid Gemini tier). */
fun defaultEscalation(primary: ItemIdentifier): ItemIdentifier? {
    val m = Config.get("SHOPLENS_ESCALATION_MODEL") ?: return null
    return when (primary) {
        is GeminiIdentifier -> primary.withModel(m)
        else -> ClaudeIdentifier(model = m)
    }
}

fun Application.module(
    identifier: ItemIdentifier = defaultIdentifier(),
    webDetection: WebDetection? = WebDetection.fromEnv(),
    jev: JevChecker? = JevChecker.fromEnvOrNull(),
    escalation: ItemIdentifier? = defaultEscalation(identifier),
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = true }) }
    install(CallLogging)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request"))
        }
        exception<com.anthropic.errors.UnauthorizedException> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Server is missing a valid ANTHROPIC_API_KEY."))
        }
        exception<com.anthropic.errors.RateLimitException> { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Rate limited by the model provider. Try again shortly."))
        }
        exception<IdentificationException> { call, e ->
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "Identification failed"))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: e.javaClass.simpleName))
        }
    }

    val service = IdentifyService(identifier, webDetection, jev, escalation)

    routing {
        staticResources("/", "static", index = "index.html")

        get("/api/health") {
            call.respond(Health("ok", webDetection != null, jev != null, (identifier as? GeminiIdentifier)?.model ?: "claude", (escalation as? GeminiIdentifier)?.model))
        }

        post("/api/identify") {
            val images = mutableListOf<ByteArray>()
            var notes: String? = null
            var verify = false

            call.receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES).forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        require(images.size < MAX_IMAGES) { "Upload at most $MAX_IMAGES photos" }
                        val bytes = part.provider().toInputStream().use { it.readBytes() }
                        if (bytes.isNotEmpty()) images += bytes
                    }
                    is PartData.FormItem -> when (part.name) {
                        "notes" -> notes = part.value
                        "verify" -> verify = part.value == "true" || part.value == "on"
                    }
                    else -> {}
                }
                part.dispose()
            }
            require(images.isNotEmpty()) { "Upload at least one photo" }

            call.respond(service.identify(images, notes, verify))
        }
    }
}

@Serializable
data class Health(val status: String, val reverseImageSearch: Boolean, val crossCheck: Boolean, val model: String, val escalationModel: String?)
