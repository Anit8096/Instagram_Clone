package com.android.insta.server.media

import com.android.insta.server.common.ApiException
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

fun testImage(width: Int, height: Int, format: String = "jpg", type: Int = BufferedImage.TYPE_INT_RGB, color: Color = Color.ORANGE): ByteArray {
    val image = BufferedImage(width, height, type)
    val g = image.createGraphics()
    g.color = color
    g.fillRect(0, 0, width / 2, height)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}

private fun ByteArray.decode(): BufferedImage = ImageIO.read(ByteArrayInputStream(this))

class ImageProcessorTest {
    private val processor = ImageProcessor()

    @Test
    fun `large image is downscaled to fit 1080x1350 and keeps aspect ratio`() {
        val result = processor.process(testImage(4000, 2500)) // 16:10, inside the allowed aspect range
        assertEquals(1080 to 675, result.width to result.height)
        assertEquals(1080, result.full.decode().width)
    }

    @Test
    fun `small image is not upscaled`() {
        val result = processor.process(testImage(600, 400))
        assertEquals(600 to 400, result.width to result.height)
    }

    @Test
    fun `very tall images are cropped to 4 by 5 and very wide ones to 1_91 by 1`() {
        val tall = processor.process(testImage(1000, 3000))
        assertEquals(1000 to 1250, tall.width to tall.height)

        val wide = processor.process(testImage(3000, 1000))
        assertEquals(1080, wide.width)
        assertEquals(565, wide.height) // 1910x1000 crop, scaled to fit 1080 wide
    }

    @Test
    fun `thumbnail is a 320 square`() {
        val thumb = processor.process(testImage(1200, 800)).thumb.decode()
        assertEquals(320 to 320, thumb.width to thumb.height)
    }

    @Test
    fun `transparent png becomes an opaque jpeg on white`() {
        val png = testImage(200, 200, format = "png", type = BufferedImage.TYPE_INT_ARGB, color = Color(0, 0, 0, 0))
        val result = processor.process(png)
        assertEquals("jpeg", ImageProcessor.detectFormat(result.full))
        val pixel = Color(result.full.decode().getRGB(10, 10))
        assertEquals(true, pixel.red > 240 && pixel.green > 240 && pixel.blue > 240)
    }

    @Test
    fun `non images and tiny images are rejected`() {
        val notImage = assertFailsWith<ApiException> { processor.process("hello world, not an image".toByteArray()) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, notImage.status)

        val tiny = assertFailsWith<ApiException> { processor.process(testImage(10, 10)) }
        assertEquals("IMAGE_TOO_SMALL", tiny.code)
    }

    @Test
    fun `format is detected from magic bytes`() {
        assertEquals("jpeg", ImageProcessor.detectFormat(testImage(40, 40)))
        assertEquals("png", ImageProcessor.detectFormat(testImage(40, 40, "png")))
        assertEquals(null, ImageProcessor.detectFormat(byteArrayOf(1, 2, 3)))
    }
}
