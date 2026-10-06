package com.android.insta.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** The largest centered [aspect] (width / height) rectangle inside a [width] x [height] image. */
fun centerCropRect(width: Int, height: Int, aspect: Float): CropRect {
    val current = width.toFloat() / height
    return when {
        current > aspect -> {
            val w = (height * aspect).roundToInt().coerceIn(1, width)
            CropRect((width - w) / 2, 0, w, height)
        }
        current < aspect -> {
            val h = (width / aspect).roundToInt().coerceIn(1, height)
            CropRect(0, (height - h) / 2, width, h)
        }
        else -> CropRect(0, 0, width, height)
    }
}

/** Turns a picked image into an upload-ready JPEG file the app owns (survives the picker's URI grant). */
interface ImageCompressor {
    /** With [aspect] (width / height), the image is center-cropped to that shape first. */
    suspend fun compress(uri: Uri, aspect: Float? = null): File
}

/**
 * Downscales to at most [maxSide] px on the long edge, applies EXIF rotation and re-encodes as JPEG.
 * Keeps uploads small (well under the server's 10 MB limit) and turns HEIC/PNG/WebP into JPEG,
 * which the server accepts. The server re-processes anyway; this mainly saves bandwidth.
 */
class AndroidImageCompressor(
    private val context: Context,
    private val maxSide: Int = 2048,
    private val quality: Int = 90,
) : ImageCompressor {

    override suspend fun compress(uri: Uri, aspect: Float?): File = withContext(Dispatchers.IO) {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeModern(uri) else decodeLegacy(uri)
        val bitmap = aspect?.let { centerCrop(decoded, it) } ?: decoded
        val dir = File(context.filesDir, "drafts").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        } finally {
            bitmap.recycle()
        }
        file
    }

    /** Recycles [source] when it returns a new bitmap. */
    private fun centerCrop(source: Bitmap, aspect: Float): Bitmap {
        val (left, top, width, height) = centerCropRect(source.width, source.height, aspect)
        if (width == source.width && height == source.height) return source
        return Bitmap.createBitmap(source, left, top, width, height).also { if (it !== source) source.recycle() }
    }

    // ImageDecoder honours EXIF orientation and can decode straight to the target size.
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = min(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    // API 27: sample down while decoding, then rotate according to EXIF.
    private fun decodeLegacy(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Unreadable image")

        val orientation = resolver.openInputStream(uri).use {
            it?.let { stream -> ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
            val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
            if (scale < 1f) postScale(scale, scale)
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }
}
