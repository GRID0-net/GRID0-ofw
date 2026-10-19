package com.grid0.ofw;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private float dragStartY;
    private int dragStartParamY;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (overlayView != null) {
            return START_NOT_STICKY;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackgroundColor(Color.argb(230, 0, 0, 0));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        layout.setLayoutParams(contentParams);

        TextView title = new TextView(this);
        title.setText("GRID0 Setup");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        layout.addView(title);

        TextView steps = new TextView(this);
        steps.setText("1. Tap ADD NETWORK\n2. Paste the Network ID (copied)\n3. Scroll down and click Add\n4. Turn on the slider next to GRID0\n5. Tap OK on the VPN prompt");
        steps.setTextColor(Color.WHITE);
        steps.setTextSize(14);
        steps.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        layout.addView(steps);

        Button done = new Button(this);
        done.setText("CLOSE");
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSelf();
            }
        });
        layout.addView(done);
        outer.addView(layout);

        TextView handle = new TextView(this);
        handle.setText("\u21D5");
        handle.setTextColor(Color.GRAY);
        handle.setTextSize(28);
        handle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                (int) (48 * getResources().getDisplayMetrics().density),
                LinearLayout.LayoutParams.MATCH_PARENT);
        handle.setLayoutParams(handleParams);
        handle.setBackgroundColor(Color.argb(255, 40, 40, 40));
        outer.addView(handle);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        overlayParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        overlayParams.y = (int) (metrics.heightPixels * 0.75) - (int) (125 * getResources().getDisplayMetrics().density);

        outer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    dragStartY = event.getRawY();
                    dragStartParamY = overlayParams.y;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int newY = (int) (dragStartParamY + (event.getRawY() - dragStartY));
                    DisplayMetrics m = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getMetrics(m);
                    if (newY < 0) {
                        newY = 0;
                    }
                    if (newY > m.heightPixels - 100) {
                        newY = m.heightPixels - 100;
                    }
                    overlayParams.y = newY;
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    return true;
                }
                return false;
            }
        });

        overlayView = outer;
        windowManager.addView(overlayView, overlayParams);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        super.onDestroy();
    }
}
