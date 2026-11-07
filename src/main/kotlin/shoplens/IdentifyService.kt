package shoplens

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/**
 * The pipeline:
 *   1. Downscale photos (saves tokens and upload time).
 *   2. One vision call returns a structured ID + price range.
 *   3. Jev cross-checks the claimed brand/model/category against the photos (cheap, calibrated).
 *   4. If Jev disagrees and an escalation model is configured, re-identify with the stronger model.
 *   5. Optional: reverse-image search + web-search verification (on request, or when confidence is low).
 *   6. Apply TheRealReal commission math to the price range.
 */
class IdentifyService(
    private val identifier: ItemIdentifier,
    private val webDetection: WebDetection?,
    private val jev: JevChecker? = null,
    private val escalation: ItemIdentifier? = null,
    private val autoVerifyBelow: Double = Config.get("SHOPLENS_AUTO_VERIFY_BELOW")?.toDoubleOrNull() ?: 0.0,
) {
    private val log = LoggerFactory.getLogger(IdentifyService::class.java)

    suspend fun identify(rawImages: List<ByteArray>, notes: String?, verifyRequested: Boolean): IdentifyResponse = coroutineScope {
        val timings: MutableMap<String, Long> = java.util.Collections.synchronizedMap(linkedMapOf())
        val usage = java.util.Collections.synchronizedList(mutableListOf<Usage>())
        val notices = mutableListOf<String>()
        suspend fun <T> timed(name: String, block: suspend () -> T): T {
            val t0 = System.nanoTime()
            return try { block() } finally { timings[name] = (System.nanoTime() - t0) / 1_000_000 }
        }

        val images = timed("prepareImages") { rawImages.map { ImagePrep.prepare(it) } }

        // Reverse-image search runs alongside the vision call; only used when verifying.
        val webMatchesJob: kotlinx.coroutines.Deferred<List<WebMatch>>? = if (verifyRequested && webDetection != null) async {
            timed<List<WebMatch>>("reverseImageSearch") {
                runCatching { webDetection.search(images.first()) }
                    .onFailure { log.warn("Web detection failed: ${it.message}") }
                    .getOrDefault(emptyList())
            }
        } else null

        var (id, idUsage) = timed("identify") { identifier.identify(images, notes) }
        usage += idUsage

        // Second opinion. A Jev failure never fails the request.
        suspend fun crossCheck(label: String, of: Identification): JevCheck? = jev?.let { j ->
            timed(label) {
                runCatching { j.check(images, of) }
                    .onFailure { log.warn("Jev check failed", it); notices += "Second opinion unavailable: ${it.message}" }
                    .getOrNull()
            }
        }?.also { usage += it.usage }

        var check = crossCheck("crossCheck", id)
        var escalatedTo: String? = null

        val needsEscalation = check?.verdict == "disagrees" ||
            (check?.verdict == "uncertain" && id.confidence.model < 0.6)
        if (escalation != null && needsEscalation) {
            runCatching { timed("escalate") { escalation.identify(images, notes) } }
                .onSuccess { (better, u) ->
                    usage += u
                    escalatedTo = u.model
                    val recheck = crossCheck("recheck", better)
                    // Keep whichever answer the independent check trusts more.
                    if (recheck == null || (recheck.brandMatch ?: 0.0) >= (check?.brandMatch ?: 0.0)) {
                        id = better; check = recheck ?: check
                    } else notices += "Escalation to ${u.model} was less convincing; kept the first answer."
                }
                .onFailure { notices += "Escalation failed: ${it.message}" }
        }

        val lowConfidence = id.confidence.model < autoVerifyBelow
        var verification: Verification? = null
        var verificationError: String? = null
        val webMatches = webMatchesJob?.await().orEmpty()

        if (verifyRequested || lowConfidence) {
            timed("verify") {
                runCatching { identifier.verify(id, webMatches) }
                    .onSuccess { (v, u) -> verification = v; usage += u }
                    .onFailure {
                        log.warn("Verification failed: ${it.message}")
                        verificationError = it.message ?: it.javaClass.simpleName
                    }
            }
        }

        val resale = verification?.adjustedResale ?: id.resale
        val payout = Payout.theRealReal(id.category, id.subcategory, resale.lowUsd, resale.typicalUsd, resale.highUsd)

        IdentifyResponse(
            identification = id,
            payout = payout,
            verification = verification,
            verificationError = verificationError,
            webMatches = webMatches,
            crossCheck = check,
            escalatedTo = escalatedTo,
            notices = notices,
            images = images.map { ImageInfo(it.width, it.height, it.bytes) },
            timingsMs = timings.toMap(),
            usage = usage.toList(),
        )
    }
}
