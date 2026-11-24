package com.kevin.phonekline;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

/** Places the same passive RemoteViews sent to LockStar over the chosen clock and background. */
final class PhoneWidgetPresentation extends FrameLayout {
    private final PhoneCandleView background;
    private RemoteViews currentFrame;
    private View widget;

    PhoneWidgetPresentation(Context context, PhoneCandleView background) {
        super(context);
        this.background = background;
        addView(background, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void showFrame(RemoteViews frame, int widthDp, int heightDp) {
        float density = getResources().getDisplayMetrics().density;
        int width = Math.max(1, Math.round(widthDp * density));
        int height = Math.max(1, Math.round(heightDp * density));
        if (frame != currentFrame) {
            if (widget != null && currentFrame.getLayoutId() == frame.getLayoutId()) {
                frame.reapply(getContext(), widget);
            } else {
                View next = frame.apply(getContext(), this);
                if (widget != null) removeView(widget);
                widget = next;
                addView(widget, new LayoutParams(width, height));
            }
            currentFrame = frame;
            background.setWidgetVisible(true);
        }
        LayoutParams params = (LayoutParams) widget.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            widget.setLayoutParams(params);
        }
    }

    void clearFrame() {
        if (widget == null) return;
        removeView(widget);
        widget = null;
        currentFrame = null;
        background.setWidgetVisible(false);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        background.layout(0, 0, right - left, bottom - top);
        if (widget == null) return;
        int width = widget.getMeasuredWidth(), height = widget.getMeasuredHeight();
        float scale = Math.min(1f, Math.min(getWidth() / (float)width, getHeight() / (float)height));
        int x = Math.round((getWidth() - width * scale) / 2);
        int y = Math.max(0, Math.min(Math.round(getHeight() * .232f), getHeight() - Math.round(height * scale)));
        widget.setPivotX(0);
        widget.setPivotY(0);
        widget.setScaleX(scale);
        widget.setScaleY(scale);
        widget.layout(x, y, x + width, y + height);
    }
}
