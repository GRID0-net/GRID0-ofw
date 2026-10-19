package com.grid0.ofw;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private static final String ZEROTIER_PACKAGE = "com.zerotier.one";
    private static final String GRID0_NETWORK_ID = "8bd5124fd68185ec";
    private boolean zeroTierInstalled = false;
    private boolean proxyRunning = false;
    private ImageView logoImage;
    private View ringView;
    private GradientDrawable ringDrawable;
    private ObjectAnimator ringAnimator;
    private TextView statusText;
    private ScrollView logScroll;
    private TextView logView;
    private Button proxyButton;

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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.CENTER;
        layout.setLayoutParams(layoutParams);
        root.addView(layout);

        FrameLayout logoFrame = new FrameLayout(this);
        int ringSize = (int) (180 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(ringSize, ringSize);
        frameParams.gravity = Gravity.CENTER;
        logoFrame.setLayoutParams(frameParams);

        ringDrawable = new GradientDrawable();
        ringDrawable.setShape(GradientDrawable.OVAL);
        ringDrawable.setColor(Color.TRANSPARENT);
        ringView = new View(this);
        ringView.setBackground(ringDrawable);
        FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(ringSize, ringSize);
        ringParams.gravity = Gravity.CENTER;
        ringView.setLayoutParams(ringParams);
        logoFrame.addView(ringView);

        logoImage = new ImageView(this);
        logoImage.setImageResource(R.drawable.logo);
        int logoSize = (int) (160 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(logoSize, logoSize);
        logoParams.gravity = Gravity.CENTER;
        logoImage.setLayoutParams(logoParams);
        logoFrame.addView(logoImage);
        layout.addView(logoFrame);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusText.setGravity(Gravity.CENTER);
        layout.addView(statusText);

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

            proxyButton = new Button(this);
            proxyButton.setText("Start Proxy");
            proxyButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            proxyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleProxy();
                }
            });
            layout.addView(proxyButton);
        } else {
            TextView msg = new TextView(this);
            msg.setText("ZeroTier is installed.\nGRID0 Network ID:\n" + GRID0_NETWORK_ID);
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

            proxyButton = new Button(this);
            proxyButton.setText("Start Proxy");
            proxyButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            proxyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleProxy();
                }
            });
            layout.addView(proxyButton);
        }

        GradientDrawable logBorder = new GradientDrawable();
        logBorder.setColor(Color.BLACK);
        int borderWidth = (int) (2 * getResources().getDisplayMetrics().density);
        logBorder.setStroke(borderWidth, Color.WHITE);
        logScroll = new ScrollView(this);
        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (200 * getResources().getDisplayMetrics().density));
        logParams.gravity = Gravity.BOTTOM;
        logScroll.setLayoutParams(logParams);
        logScroll.setBackground(logBorder);
        logScroll.setVisibility(View.GONE);
        logView = new TextView(this);
        logView.setText("");
        logView.setTextColor(Color.WHITE);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        int logPad = (int) (8 * getResources().getDisplayMetrics().density);
        logView.setPadding(logPad, logPad, logPad, logPad);
        logScroll.addView(logView);
        root.addView(logScroll);

        setContentView(root);
        updateProxyUI();
    }

    private void toggleProxy() {
        proxyRunning = !proxyRunning;
        if (proxyRunning) {
            writeProxyParams();
        } else {
            if (logView != null) {
                logView.setText("");
            }
        }
        updateProxyUI();
    }

    private void writeProxyParams() {
        String phoneIp = getPhoneWifiIp();
        String ztIp = getZeroTierIp();
        if (logView != null) {
            logView.setText("PHONE_IP = " + phoneIp + "\nZT_IP = " + ztIp + "\n");
        }
    }

    private String getPhoneWifiIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                }
            }
        } catch (Exception e) {
        }
        return "not found";
    }

    private String getZeroTierIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    if (host == null) {
                        continue;
                    }
                    if (host.startsWith("10.147.") || ni.getName().toLowerCase().contains("zt")) {
                        return host;
                    }
                }
            }
        } catch (Exception e) {
        }
        return "not found";
    }

    private void updateProxyUI() {
        if (proxyButton != null) {
            proxyButton.setText(proxyRunning ? "Stop Proxy" : "Start Proxy");
        }
        if (logScroll != null) {
            logScroll.setVisibility(proxyRunning ? View.VISIBLE : View.GONE);
        }
        if (statusText != null) {
            statusText.setText(proxyRunning ? "ON" : "OFF");
            statusText.setTextColor(proxyRunning ? Color.RED : Color.GRAY);
        }
        if (logoImage != null) {
            if (proxyRunning) {
                logoImage.clearColorFilter();
            } else {
                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(0);
                logoImage.setColorFilter(new ColorMatrixColorFilter(matrix));
            }
        }
        if (ringDrawable != null && ringView != null) {
            int strokeWidth = (int) (4 * getResources().getDisplayMetrics().density);
            int dashWidth = (int) (12 * getResources().getDisplayMetrics().density);
            int dashGap = (int) (8 * getResources().getDisplayMetrics().density);
            if (proxyRunning) {
                ringDrawable.setStroke(strokeWidth, Color.RED, dashWidth, dashGap);
                if (ringAnimator == null) {
                    ringAnimator = ObjectAnimator.ofFloat(ringView, "rotation", 0f, 360f);
                    ringAnimator.setDuration(1500);
                    ringAnimator.setRepeatCount(ValueAnimator.INFINITE);
                    ringAnimator.setInterpolator(new LinearInterpolator());
                }
                if (!ringAnimator.isRunning()) {
                    ringAnimator.start();
                }
            } else {
                if (ringAnimator != null && ringAnimator.isRunning()) {
                    ringAnimator.cancel();
                }
                ringDrawable.setStroke(strokeWidth, Color.GRAY, dashWidth, dashGap);
            }
        }
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
        startService(new Intent(this, VpnMonitorService.class));
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
