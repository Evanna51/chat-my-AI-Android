package com.example.aichat

import android.view.MotionEvent
import android.widget.TextView
import androidx.annotation.Nullable

object FormInputScrollHelper {

    @JvmStatic
    fun enableFor(@Nullable vararg fields: TextView?) {
        for (field in fields) {
            if (field == null) continue
            field.isVerticalScrollBarEnabled = true
            field.setOnTouchListener { v, event ->
                if (event == null) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val parent = v.parent
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val parent = v.parent
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }
    }
}
