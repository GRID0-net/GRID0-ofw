package com.grid0.ofw;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String ZEROTIER_PACKAGE = "com.zerotier.one";
    private static final String GRID0_NETWORK_ID = "8bd5124fd68185ec";
    private boolean zeroTierInstalled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        zeroTierInstalled = isZeroTierInstalled();
        buildLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean nowInstalled = isZeroTierInstalled();
        if (nowInstalled != zeroTierInstalled) {
            zeroTierInstalled = nowInstalled;
            if (nowInstalled) {
                Toast.makeText(this, "ZeroTier installed, continue to Step 1", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "ZeroTier was uninstalled, install it again to continue", Toast.LENGTH_LONG).show();
            }
            buildLayout();
        }
    }

    private void buildLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.BLACK);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        int logoSize = (int) (160 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.gravity = Gravity.CENTER;
        logo.setLayoutParams(logoParams);
        layout.addView(logo);

        if (!zeroTierInstalled) {
            TextView msg = new TextView(this);
            msg.setText("GRID0 needs ZeroTier to connect. Install ZeroTier One from the Play Store.");
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(16);
            msg.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            msg.setGravity(Gravity.CENTER);
            layout.addView(msg);

            TextView step1 = new TextView(this);
            step1.setText("Step 1");
            step1.setTextColor(Color.WHITE);
            step1.setTextSize(16);
            step1.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step1.setGravity(Gravity.CENTER);
            layout.addView(step1);

            Button install = new Button(this);
            install.setText("Get ZeroTier");
            install.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            install.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openZeroTierStore();
                }
            });
            layout.addView(install);

            TextView step2 = new TextView(this);
            step2.setText("Step 2");
            step2.setTextColor(Color.WHITE);
            step2.setTextSize(16);
            step2.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step2.setGravity(Gravity.CENTER);
            layout.addView(step2);

            Button proxy = new Button(this);
            proxy.setText("Start Proxy");
            proxy.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            proxy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                }
            });
            layout.addView(proxy);
        } else {
            TextView msg = new TextView(this);
            msg.setText("ZeroTier is installed.\nGRID0 Network ID:\n" + GRID0_NETWORK_ID);
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(16);
            msg.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            msg.setGravity(Gravity.CENTER);
            layout.addView(msg);

            TextView hint = new TextView(this);
            hint.setText("Tap Connect. The Network ID is copied and a guide appears over ZeroTier. In ZeroTier, tap ADD NETWORK, paste the ID, and enable the connection.");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(14);
            hint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            hint.setGravity(Gravity.CENTER);
            layout.addView(hint);

            TextView step1 = new TextView(this);
            step1.setText("Step 1");
            step1.setTextColor(Color.WHITE);
            step1.setTextSize(16);
            step1.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step1.setGravity(Gravity.CENTER);
            layout.addView(step1);

            Button connect = new Button(this);
            connect.setText("Connect to GRID0 Network");
            connect.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            connect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connectToGrid0Network();
                }
            });
            layout.addView(connect);

            TextView step2 = new TextView(this);
            step2.setText("Step 2");
            step2.setTextColor(Color.WHITE);
            step2.setTextSize(16);
            step2.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step2.setGravity(Gravity.CENTER);
            layout.addView(step2);

            Button proxy = new Button(this);
            proxy.setText("Start Proxy");
            proxy.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            proxy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                }
            });
            layout.addView(proxy);
        }

        setContentView(layout);
    }

    private boolean isZeroTierInstalled() {
        try {
            getPackageManager().getPackageInfo(ZEROTIER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openZeroTierStore() {
        try {
            Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ZEROTIER_PACKAGE));
            store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(store);
        } catch (android.content.ActivityNotFoundException e) {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ZEROTIER_PACKAGE));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(web);
        }
    }

    private void connectToGrid0Network() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable Display over other apps for GRID0, then tap Connect again", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("GRID0 Network ID", GRID0_NETWORK_ID));
        Toast.makeText(this, "Network ID already copied", Toast.LENGTH_SHORT).show();
        startService(new Intent(this, OverlayService.class));
        Intent launch = getPackageManager().getLaunchIntentForPackage(ZEROTIER_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } else {
            stopService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Could not open ZeroTier", Toast.LENGTH_SHORT).show();
        }
    }
}
