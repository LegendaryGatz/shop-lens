package shoplens

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** A photo ready to send to the model: base64 JPEG plus its final size. */
data class PreparedImage(val base64Jpeg: String, val width: Int, val height: Int, val bytes: Int)

/**
 * Downscales and re-encodes uploads before they reach the model.
 *
 * Image input is billed by pixel area (roughly width*height/750 tokens), so a
 * 12MP phone photo costs far more than it needs to. ~1568px on the long edge is
 * the largest size the model uses without resizing it again server-side, and
 * it keeps small label/tag text legible.
 */
object ImagePrep {
    const val MAX_EDGE = 1568
    private const val JPEG_QUALITY = 0.85f

    fun prepare(raw: ByteArray, maxEdge: Int = MAX_EDGE): PreparedImage {
        val src = ImageIO.read(ByteArrayInputStream(raw))
            ?: throw IllegalArgumentException("Unsupported or corrupt image (use JPEG, PNG, WebP, GIF or BMP)")

        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)

        // Draw onto an RGB canvas: drops alpha (JPEG has none) and resizes in one pass.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = java.awt.Color.WHITE
            fillRect(0, 0, w, h)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }

        val jpeg = encodeJpeg(out)
        return PreparedImage(Base64.getEncoder().encodeToString(jpeg), w, h, jpeg.size)
    }

    private fun encodeJpeg(img: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val buf = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(buf).use { ios ->
            writer.output = ios
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = JPEG_QUALITY
            }
            writer.write(null, IIOImage(img, null, null), params)
        }
        writer.dispose()
        return buf.toByteArray()
    }
}
