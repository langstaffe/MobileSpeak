package dev.mobilespeak.mobilespeak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

internal data class AvatarCrop(val x: Float = .5f, val y: Float = .5f, val zoom: Float = 1f) {
    fun rect(width: Int, height: Int): android.graphics.Rect {
        val side = (minOf(width, height) / zoom.coerceIn(1f, 4f)).toInt().coerceAtLeast(1)
        val left = (x * width - side / 2f).toInt().coerceIn(0, width - side)
        val top = (y * height - side / 2f).toInt().coerceIn(0, height - side)
        return android.graphics.Rect(left, top, left + side, top + side)
    }
    fun transform(width: Int, height: Int, viewport: Float, panX: Float = 0f, panY: Float = 0f,
        factor: Float = 1f, focusX: Float = viewport / 2, focusY: Float = viewport / 2): AvatarCrop {
        val nextZoom = (zoom * factor).coerceIn(1f, 4f)
        val oldSide = minOf(width, height) / zoom.coerceIn(1f, 4f)
        val side = minOf(width, height) / nextZoom
        val cx = (x * width + (focusX / viewport - .5f) * (oldSide - side) - panX / viewport * side).coerceIn(side / 2, width - side / 2)
        val cy = (y * height + (focusY / viewport - .5f) * (oldSide - side) - panY / viewport * side).coerceIn(side / 2, height - side / 2)
        return AvatarCrop(cx / width, cy / height, nextZoom)
    }
}

internal object AvatarImages {
    const val currentName = "default-avatar-current"
    const val previewName = "default-avatar-preview.jpg"
    const val uploadName = "default-avatar-upload.jpg"
    private const val maxLocalBytes = 8 * 1024 * 1024

    fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        require(resolver.getType(uri) != "image/gif") { "Animated images are not supported" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: error("Unable to read image")).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outMimeType !in listOf("image/gif")) { "Unsupported image" }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Unable to decode image")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        val upright = try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        catch (error: Throwable) { bitmap.recycle(); throw error }
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    fun prepare(context: Context, uri: Uri): Pair<ByteArray, List<ByteArray>> = load(context, uri).let { image ->
        try { prepare(image, AvatarCrop()) } finally { image.recycle() }
    }

    fun prepare(source: Bitmap, crop: AvatarCrop): Pair<ByteArray, List<ByteArray>> {
        val rect = crop.rect(source.width, source.height)
        val square = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        try {
            val preview = jpeg(square, 640, 90)
            val uploads = listOf(2048 to 90, 768 to 80, 256 to 65).map { (size, quality) -> jpeg(square, size, quality) }
            require(uploads.all { it.isNotEmpty() && it.size <= maxLocalBytes }) { "Image exceeds local avatar safety limit" }
            return preview to uploads
        } finally { if (square !== source) square.recycle() }
    }

    private fun jpeg(source: Bitmap, maxSide: Int, quality: Int): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(result).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), null)
            }
            return ByteArrayOutputStream().also { check(result.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()
        } finally {
            result.recycle()
        }
    }
}
