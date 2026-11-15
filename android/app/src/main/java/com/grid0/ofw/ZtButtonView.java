package com.grid0.ofw;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class ZtButtonView extends View {
    private static final int ORANGE = 0xFFFFB25B;
    private static final int RING_COUNT = 6;
    private static final float CYCLE_MS = 4200f;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap logo;
    private final Bitmap logoGrey;
    private final RectF logoRect = new RectF();
    private boolean connected = false;
    private ValueAnimator animator;
    private float phase = 0f;
    private long startMs = 0L;

    public ZtButtonView(Context context) {
        super(context);
        setClickable(true);
        setContentDescription("Open ZeroTier");
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(ORANGE);
        logo = BitmapFactory.decodeResource(getResources(), R.drawable.zt_logo);
        logoGrey = Bitmap.createBitmap(logo.getWidth(), logo.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas greyCanvas = new Canvas(logoGrey);
        Paint greyPaint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        greyPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
        greyCanvas.drawBitmap(logo, 0, 0, greyPaint);
    }

    public void setConnected(boolean c) {
        if (connected != c) {
            connected = c;
            invalidate();
        }
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
        startMs = System.currentTimeMillis();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration((long) CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (Float) animation.getAnimatedValue();
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
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        float logoHalf = 23f * density;
        logoRect.set(cx - logoHalf, cy - logoHalf, cx + logoHalf, cy + logoHalf);
        canvas.drawBitmap(connected ? logo : logoGrey, null, logoRect, logoPaint);

        float rOut = 43f * density;
        float rIn = 25.5f * density;
        float spin = (System.currentTimeMillis() - startMs) / 1000f * 10f;
        for (int i = 0; i < RING_COUNT; i++) {
            float p = (phase + i / (float) RING_COUNT) % 1f;
            float r = rOut + (rIn - rOut) * p;
            float sqDp = 3.4f - 2.9f * p;
            float alpha = 0.95f * Math.min(p / 0.1f, 1f) * Math.min((1f - p) / 0.25f, 1f);
            if (alpha <= 0f) {
                continue;
            }
            dotPaint.setAlpha((int) (alpha * 255));
            drawDotRing(canvas, cx, cy, r, sqDp * density, spin + i * 20f, p);
        }
    }

    private void drawDotRing(Canvas canvas, float cx, float cy, float r, float sq, float spinDeg, float wavePhase) {
        float density = getResources().getDisplayMetrics().density;
        int n = Math.max(18, (int) Math.round((2 * Math.PI * (r / density)) / 6f));
        double spin = Math.toRadians(spinDeg);
        for (int j = 0; j < n; j++) {
            double a = (j / (double) n) * 2 * Math.PI - Math.PI / 2 + spin;
            float wobble = 1f + 0.03f * (float) Math.sin(2 * Math.PI * (j / (float) n * 4f + wavePhase));
            float rr = r * wobble;
            float x = cx + rr * (float) Math.cos(a);
            float y = cy + rr * (float) Math.sin(a);
            canvas.drawCircle(x, y, sq / 2f, dotPaint);
        }
    }
}
