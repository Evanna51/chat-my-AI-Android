package com.example.aichat.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.PopupWindow
import com.example.aichat.R

/**
 * Floating action toolbar shown above a user message bubble on long-press.
 *
 * Behavior:
 * - White pill with 5 icons (copy / edit / regenerate / outline / delete)
 * - Anchors above the long-pressed bubble; falls back to below if not enough
 *   space at the top.
 * - Outside touch dismisses (PopupWindow default).
 * - One popup per chat screen; calling [show] dismisses any prior instance.
 */
class MessageActionPopup(private val context: Context) {

    interface Listener {
        fun onCopy()
        fun onEdit()
        fun onRegenerate()
        fun onOutline()
        fun onDelete()
    }

    private var window: PopupWindow? = null

    fun isShowing(): Boolean = window?.isShowing == true

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    fun show(anchor: View, writerMode: Boolean, listener: Listener) {
        dismiss()
        val inflater = LayoutInflater.from(context)
        val content = inflater.inflate(R.layout.popup_message_user_actions, null, false)

        content.findViewById<ImageButton>(R.id.popupActionCopy).setOnClickListener {
            listener.onCopy(); dismiss()
        }
        content.findViewById<ImageButton>(R.id.popupActionEdit).setOnClickListener {
            listener.onEdit(); dismiss()
        }
        content.findViewById<ImageButton>(R.id.popupActionRegenerate).setOnClickListener {
            listener.onRegenerate(); dismiss()
        }
        val outlineBtn = content.findViewById<ImageButton>(R.id.popupActionOutline)
        outlineBtn.visibility = if (writerMode) View.VISIBLE else View.GONE
        outlineBtn.setOnClickListener { listener.onOutline(); dismiss() }
        content.findViewById<ImageButton>(R.id.popupActionDelete).setOnClickListener {
            listener.onDelete(); dismiss()
        }

        val pw = PopupWindow(content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true /* focusable */)
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.elevation = 8f * context.resources.displayMetrics.density
        window = pw

        // Measure to determine where to position above the bubble.
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = content.measuredHeight
        val popupWidth = content.measuredWidth

        val anchorRect = Rect()
        anchor.getGlobalVisibleRect(anchorRect)
        val gap = (6 * context.resources.displayMetrics.density).toInt()

        val displayRect = Rect()
        anchor.getWindowVisibleDisplayFrame(displayRect)
        val showAbove = anchorRect.top - displayRect.top >= popupHeight + gap

        // Right-align with the bubble (user messages are end-aligned).
        val xOffset = anchorRect.right - popupWidth
        val yOffset = if (showAbove) anchorRect.top - popupHeight - gap
                      else anchorRect.bottom + gap

        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, xOffset, yOffset)
    }
}
