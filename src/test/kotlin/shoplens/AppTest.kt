package shoplens

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeIdentifier : ItemIdentifier {
    var lastImageCount = 0
    override suspend fun identify(images: List<PreparedImage>, userNotes: String?): Pair<Identification, Usage> {
        lastImageCount = images.size
        return sampleId() to Usage("fake", 1000, 500, 0.0)
    }
    override suspend fun verify(id: Identification, webMatches: List<WebMatch>) =
        Verification("Speedy Bandoulière 30", null, 2030, emptyList(), null, "ok") to Usage("fake", 10, 10, 0.0)
}

fun sampleId() = Identification(
    summary = "Louis Vuitton Speedy 30 in Monogram canvas", category = "handbags", subcategory = "top handle bag",
    brand = "Louis Vuitton", designer = null, modelOrLine = "Speedy 30", collectionOrSeason = null, yearRange = "2010-2015",
    gender = "womens", materials = listOf("Monogram coated canvas"), colors = listOf("brown"), hardware = "brass",
    size = null, visibleText = listOf("SP0122"), identifyingFeatures = emptyList(),
    condition = Condition("very_good", "corner wear"), authenticity = Authenticity(emptyList(), emptyList(), emptyList()),
    confidence = Confidence(0.98, 0.9, 0.0), alternatives = emptyList(), originalRetailUsd = 1500,
    resale = ResaleEstimate(450, 600, 800, "high", "staple model"), photoRequests = emptyList(), searchQueries = emptyList(),
)

fun pngBytes(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
    ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it)
}.toByteArray()

class PayoutTest {
    @Test fun tiers() {
        assertEquals(20, TheRealRealCommission.keepPercent(99, CommissionClass.HANDBAGS))
        assertEquals(60, TheRealRealCommission.keepPercent(600, CommissionClass.HANDBAGS))
        assertEquals(80, TheRealRealCommission.keepPercent(9000, CommissionClass.HANDBAGS))
        assertEquals(85, TheRealRealCommission.keepPercent(8000, CommissionClass.WATCHES))
    }
    @Test fun classes() {
        assertEquals(CommissionClass.WATCHES, TheRealRealCommission.classFor("watches", "dive watch"))
        assertEquals(CommissionClass.MENS_SNEAKERS, TheRealRealCommission.classFor("shoes", "men's sneakers"))
        assertEquals(CommissionClass.CLOTHING_SHOES_ACCESSORIES, TheRealRealCommission.classFor("shoes", "women's sneakers"))
        assertEquals(CommissionClass.CLOTHING_SHOES_ACCESSORIES, TheRealRealCommission.classFor("clothing", "blazer"))
    }
    @Test fun math() {
        val p = Payout.theRealReal("handbags", null, 450, 600, 800)
        assertEquals(360, p.typical.payout)     // 600 * 60%
        assertEquals(520, p.high.payout)         // 800 * 65%
    }
}

class ImagePrepTest {
    @Test fun downscalesLongEdge() {
        val p = ImagePrep.prepare(pngBytes(4000, 3000))
        assertEquals(1568, p.width)
        assertEquals(1176, p.height)
    }
    @Test fun keepsSmallImages() {
        val p = ImagePrep.prepare(pngBytes(800, 600))
        assertEquals(800, p.width)
    }
}

class AppTest {
    @Test fun identifyEndpoint() = testApplication {
        val fake = FakeIdentifier()
        application { module(identifier = fake, webDetection = null, jev = null, escalation = null) }

        val res = client.submitFormWithBinaryData(
            url = "/api/identify",
            formData = formData {
                append("notes", "from my closet")
                append("verify", "true")
                repeat(2) {
                    append("photo", pngBytes(2000, 1000), Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"p$it.png\"")
                    })
                }
            },
        )
        val body = res.bodyAsText()
        assertEquals(HttpStatusCode.OK, res.status, body)
        assertEquals(2, fake.lastImageCount)
        assertTrue(body.contains("\"payout\":360"), body)            // typical: 600 * 60%
        assertTrue(body.contains("Speedy Bandoulière 30"), body)     // verification ran
    }

    @Test fun rejectsEmptyUpload() = testApplication {
        application { module(identifier = FakeIdentifier(), webDetection = null, jev = null, escalation = null) }
        val res = client.submitFormWithBinaryData("/api/identify", formData { append("notes", "x") })
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test fun servesUi() = testApplication {
        application { module(identifier = FakeIdentifier(), webDetection = null, jev = null, escalation = null) }
        assertTrue(client.get("/").bodyAsText().contains("Shop Lens"))
    }
}
