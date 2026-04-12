package com.example.aichat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import com.example.aichat.R

object AssistantAvatarHelper {

    @JvmStatic
    fun bindAvatar(imageView: ImageView?, textView: TextView?, assistant: MyAssistant?, fallbackName: String?) {
        // 圆形背景 + 最浅主题色
        val context = imageView?.context ?: textView?.context
        if (context != null) {
            val tv = TypedValue()
            context.theme.resolveAttribute(R.attr.colorPrimarySubtle, tv, true)
            val subtleColor = tv.data
            listOfNotNull(imageView, textView).forEach { v ->
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                bg.setColor(subtleColor)
                v.background = bg
                v.clipToOutline = true
            }
        }

        val imageBase64 = if (assistant != null) safe(assistant.avatarImageBase64) else ""
        val avatarBitmap = decodeBase64(imageBase64)
        if (avatarBitmap != null && imageView != null) {
            imageView.setImageBitmap(avatarBitmap)
            imageView.visibility = ImageView.VISIBLE
            textView?.visibility = TextView.GONE
            return
        }
        imageView?.visibility = ImageView.GONE
        if (textView != null) {
            textView.text = resolveTextAvatar(assistant, fallbackName)
            textView.visibility = TextView.VISIBLE
        }
    }

    @JvmStatic
    fun resolveTextAvatar(assistant: MyAssistant?, fallbackName: String?): String {
        val avatarText = if (assistant != null) safe(assistant.avatar) else ""
        if (avatarText.isNotEmpty()) return avatarText
        val fallback = safe(fallbackName)
        if (fallback.isNotEmpty()) return fallback.substring(0, 1)
        return "助"
    }

    @JvmStatic
    fun decodeBase64(base64: String?): Bitmap? {
        if (base64.isNullOrEmpty() || base64.trim().isEmpty()) return null
        return try {
            val data = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun safe(value: String?): String = value?.trim() ?: ""
}
