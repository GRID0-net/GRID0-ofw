package com.grid0.ofw;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View overlayView;

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
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.argb(230, 0, 0, 0));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

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
        done.setText("Got it");
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSelf();
            }
        });
        layout.addView(done);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = (int) (metrics.heightPixels * 0.75) - (int) (125 * getResources().getDisplayMetrics().density);
        overlayView = layout;
        windowManager.addView(overlayView, params);
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
