package com.example.aichat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 头像图片处理工具：从 EditMyAssistantActivity 抽出，让 SessionChatSettingsActivity 等多处复用。
 * 全部静态，无 Activity 依赖。
 */
object AvatarImageUtils {

    @JvmStatic
    fun compressUriToBase64(
        contentResolver: ContentResolver,
        uri: Uri,
        maxSize: Int = 256,
        quality: Int = 80,
    ): String? {
        val bitmap = decodeSampledBitmap(contentResolver, uri, maxSize) ?: return null
        val squared = cropCenterSquare(bitmap) ?: return null
        val scaled = scaleBitmapWithin(squared, maxSize)
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @JvmStatic
    fun compressBitmapToBase64(
        bitmap: Bitmap?,
        maxSize: Int = 256,
        quality: Int = 85,
    ): String? {
        if (bitmap == null) return null
        val squared = cropCenterSquare(bitmap) ?: return null
        val scaled = scaleBitmapWithin(squared, maxSize)
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @JvmStatic
    fun decodeSampledBitmap(contentResolver: ContentResolver, uri: Uri, reqSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { stream: InputStream? ->
                if (stream == null) return null
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val decode = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, reqSize, reqSize)
            }
            contentResolver.openInputStream(uri).use { stream: InputStream? ->
                if (stream == null) return null
                BitmapFactory.decodeStream(stream, null, decode)
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    @JvmStatic
    fun cropCenterSquare(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val size = min(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    @JvmStatic
    fun scaleBitmapWithin(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap
        val ratio = width.toFloat() / height.toFloat()
        val (targetWidth, targetHeight) = if (ratio > 1f) {
            maxSize to (maxSize / ratio).roundToInt()
        } else {
            (maxSize * ratio).roundToInt() to maxSize
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
