package com.android.insta.server.media

import com.android.insta.server.common.ApiException
import io.ktor.http.HttpStatusCode
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.geometry.Positions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class ProcessedImage(val full: ByteArray, val thumb: ByteArray, val width: Int, val height: Int)

data class CroppedImage(val full: ByteArray, val width: Int, val height: Int)

/**
 * Normalizes uploads: applies EXIF orientation, flattens transparency onto white and re-encodes to
 * JPEG. Re-encoding drops all metadata (GPS included). Produces a display image that fits 1080x1350
 * (never upscaled) and a 320x320 center-cropped grid thumbnail.
 */
class ImageProcessor {

    fun process(bytes: ByteArray): ProcessedImage {
        if (detectFormat(bytes) == null) {
            throw ApiException(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA", "Only JPEG, PNG or WebP images are accepted")
        }
        checkDimensions(bytes)

        val oriented = try {
            Thumbnails.of(ByteArrayInputStream(bytes)).scale(1.0).useExifOrientation(true).asBufferedImage()
        } catch (e: Exception) {
            throw invalidImage()
        }
        val flat = clampAspect(flatten(oriented))

        val display = if (flat.width <= FULL_MAX_W && flat.height <= FULL_MAX_H) {
            flat
        } else {
            Thumbnails.of(flat).size(FULL_MAX_W, FULL_MAX_H).keepAspectRatio(true).asBufferedImage()
        }
        val full = encode(display, FULL_QUALITY)
        val thumb = ByteArrayOutputStream().also {
            Thumbnails.of(flat).size(THUMB_SIZE, THUMB_SIZE).crop(Positions.CENTER)
                .outputFormat("jpg").outputQuality(THUMB_QUALITY).toOutputStream(it)
        }.toByteArray()
        return ProcessedImage(full, thumb, display.width, display.height)
    }

    /**
     * Center-crops an already processed display image to [aspect] (width / height) for a carousel, whose items all
     * share the cover's shape. Null when it's already within 1 % of it. The thumbnail is a square crop either way.
     */
    fun cropToAspect(display: ByteArray, aspect: Double): CroppedImage? {
        val source = try {
            ImageIO.read(ByteArrayInputStream(display)) ?: throw invalidImage()
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw invalidImage()
        }
        val current = source.width.toDouble() / source.height
        if (kotlin.math.abs(current - aspect) / aspect < ASPECT_TOLERANCE) return null
        val cropped = if (current > aspect) {
            val width = (source.height * aspect).toInt().coerceAtLeast(1)
            source.getSubimage((source.width - width) / 2, 0, width, source.height)
        } else {
            val height = (source.width / aspect).toInt().coerceAtLeast(1)
            source.getSubimage(0, (source.height - height) / 2, source.width, height)
        }
        return CroppedImage(encode(cropped, FULL_QUALITY), cropped.width, cropped.height)
    }

    /** Reads only the header so a tiny file claiming 50000x50000 px is rejected before decoding (decompression bombs). */
    private fun checkDimensions(bytes: ByteArray) {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw invalidImage()
        input.use {
            val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: throw invalidImage()
            try {
                reader.input = it
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w < MIN_SIDE || h < MIN_SIDE) {
                    throw ApiException(HttpStatusCode.UnprocessableEntity, "IMAGE_TOO_SMALL", "Image must be at least ${MIN_SIDE}px on each side")
                }
                if (w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) {
                    throw ApiException(HttpStatusCode.UnprocessableEntity, "IMAGE_TOO_LARGE", "Image dimensions are too large")
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw invalidImage()
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * Center-crops extreme shapes into the feed's range: no taller than 4:5, no wider than 1.91:1.
     * Without this, a phone screenshot (~9:20) fills the whole screen and pushes captions off it.
     */
    private fun clampAspect(source: BufferedImage): BufferedImage {
        val aspect = source.width.toDouble() / source.height
        return when {
            aspect < MIN_ASPECT -> {
                val height = (source.width / MIN_ASPECT).toInt()
                source.getSubimage(0, (source.height - height) / 2, source.width, height)
            }
            aspect > MAX_ASPECT -> {
                val width = (source.height * MAX_ASPECT).toInt()
                source.getSubimage((source.width - width) / 2, 0, width, source.height)
            }
            else -> source
        }
    }

    private fun flatten(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, source.width, source.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return rgb
    }

    private fun encode(image: BufferedImage, quality: Float): ByteArray = ByteArrayOutputStream().also {
        Thumbnails.of(image).scale(1.0).outputFormat("jpg").outputQuality(quality).toOutputStream(it)
    }.toByteArray()

    private fun invalidImage() = ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_IMAGE", "The file is not a readable image")

    companion object {
        const val FULL_MAX_W = 1080
        const val FULL_MAX_H = 1350
        const val THUMB_SIZE = 320
        private const val FULL_QUALITY = 0.85f
        private const val THUMB_QUALITY = 0.8f
        private const val MIN_SIDE = 32
        private const val MAX_SIDE = 12_000
        private const val MAX_PIXELS = 60_000_000L
        const val MIN_ASPECT = 0.8 // 4:5 portrait
        const val MAX_ASPECT = 1.91 // landscape
        private const val ASPECT_TOLERANCE = 0.01

        /** Identifies JPEG/PNG/WebP by magic bytes; never trust the client's Content-Type. */
        fun detectFormat(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
    }
}
