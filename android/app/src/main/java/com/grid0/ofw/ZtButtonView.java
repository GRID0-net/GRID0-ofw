package com.grid0.ofw;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class ZtButtonView extends View {
    private static final int ORANGE = 0xFFFFB515;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private ValueAnimator animator;
    private float angle = 0f;

    public ZtButtonView(Context context) {
        super(context);
        setClickable(true);
        setContentDescription("Open ZeroTier");
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(ORANGE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        discPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(ORANGE);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            startSpin();
        } else {
            stopSpin();
        }
    }

    private void startSpin() {
        if (animator != null) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(7000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                angle = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void stopSpin() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float maxR = Math.min(w, h) / 2f;

        discPaint.setColor(0xFF1B1B1B);
        canvas.drawCircle(cx, cy, maxR * 0.62f, discPaint);
        ringPaint.setStrokeWidth(maxR * 0.045f);
        canvas.drawCircle(cx, cy, maxR * 0.62f, ringPaint);

        textPaint.setTextSize(maxR * 0.52f);
        float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText("ZT", cx, ty, textPaint);

        float[] radii = {0.76f, 0.87f, 0.98f};
        float[] sweeps = {250f, 200f, 290f};
        float[] speeds = {1f, -0.7f, 0.5f};
        for (int i = 0; i < 3; i++) {
            float r = maxR * radii[i];
            oval.set(cx - r, cy - r, cx + r, cy + r);
            ringPaint.setStrokeWidth(maxR * 0.05f);
            canvas.drawArc(oval, angle * speeds[i] + i * 120f, sweeps[i], false, ringPaint);
        }
    }
}
