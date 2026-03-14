package com.example.aichat;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.Nullable;

public final class FormInputScrollHelper {
    private FormInputScrollHelper() {}

    public static void enableFor(@Nullable TextView... fields) {
        if (fields == null) return;
        for (TextView field : fields) {
            if (field == null) continue;
            field.setVerticalScrollBarEnabled(true);
            field.setOnTouchListener((v, event) -> {
                if (event == null) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                    ViewParent parent = v.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    ViewParent parent = v.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                return false;
            });
        }
    }
}
