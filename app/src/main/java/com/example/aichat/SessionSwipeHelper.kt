package com.example.aichat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Swipe-to-action helper for the session list.
 *
 * Left swipe reveals two action zones drawn behind the item:
 *   - Partial swipe (< 50% width): "Pin" (blue) + "Hide" (gray)
 *   - Full swipe   (>= 50% width): "Delete" (red)
 *
 * Releasing in the partial zone triggers nothing automatically — the user
 * must tap the revealed button area. On full swipe (`onSwiped`), the
 * delete callback fires.
 *
 * Because [ItemTouchHelper] only delivers a single `onSwiped` event (at the
 * swipe-threshold boundary), the partial-swipe buttons are handled via a
 * click detector installed on the [RecyclerView].
 */
class SessionSwipeHelper(
    private val context: Context,
    private val onPin: (position: Int) -> Unit,
    private val onHide: (position: Int) -> Unit,
    private val onDelete: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val deleteColor = ContextCompat.getColor(context, R.color.ios_destructive)
    private val pinColor = ContextCompat.getColor(context, R.color.primary)
    private val hideColor = ContextCompat.getColor(context, R.color.ios_section_label)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * context.resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val density = context.resources.displayMetrics.density
    private val buttonWidth = 72 * density  // 72dp per button

    // ---- ItemTouchHelper callbacks ----

    override fun onMove(
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        onDelete(pos)
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.5f

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val itemHeight = (itemView.bottom - itemView.top).toFloat()
        val absX = -dX  // positive value for left swipe

        if (absX > 0) {
            val right = itemView.right.toFloat()
            val top = itemView.top.toFloat()
            val bottom = itemView.bottom.toFloat()
            val textY = top + itemHeight / 2f + textPaint.textSize / 3f

            if (absX < buttonWidth * 2) {
                // Partial swipe — show Pin + Hide buttons
                // Pin button (rightmost)
                val pinLeft = (right - buttonWidth).coerceAtLeast(right - absX)
                paint.color = pinColor
                c.drawRect(pinLeft, top, right, bottom, paint)
                if (right - pinLeft > buttonWidth * 0.4f) {
                    c.drawText("置顶", (pinLeft + right) / 2f, textY, textPaint)
                }

                // Hide button (left of Pin)
                if (absX > buttonWidth) {
                    val hideLeft = (right - buttonWidth * 2).coerceAtLeast(right - absX)
                    paint.color = hideColor
                    c.drawRect(hideLeft, top, pinLeft, bottom, paint)
                    if (pinLeft - hideLeft > buttonWidth * 0.4f) {
                        c.drawText("隐藏", (hideLeft + pinLeft) / 2f, textY, textPaint)
                    }
                }
            } else {
                // Full swipe — show Delete background
                paint.color = deleteColor
                c.drawRect(right - absX, top, right, bottom, paint)
                c.drawText("删除", right - absX / 2f, textY, textPaint)
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
