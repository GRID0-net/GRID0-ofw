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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String ZEROTIER_PACKAGE = "com.zerotier.one";
    private static final String GRID0_NETWORK_ID = "8bd5124fd68185ec";
    private boolean zeroTierInstalled = false;
    private boolean proxyRunning = false;
    private boolean isolationError = false;
    private String logText = "";
    private int currentTab = 0;
    private ImageView logoImage;
    private View ringView;
    private GradientDrawable ringDrawable;
    private ObjectAnimator ringAnimator;
    private TextView statusText;
    private ScrollView logScroll;
    private TextView logView;
    private Button proxyButton;
    private Button tabHome;
    private Button tabMore;
    private LinearLayout homeContent;
    private LinearLayout moreContent;
    private Map<String, TextView> moreValues = new HashMap<>();

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
        }
        buildLayout();
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        main.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams mainParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        mainParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        mainParams.topMargin = (int) (48 * getResources().getDisplayMetrics().density);
        main.setLayoutParams(mainParams);
        root.addView(main);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabHome = new Button(this);
        tabHome.setText("HOME");
        tabHome.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tabHome.setLayoutParams(tabParams);
        tabHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = 0;
                showTab();
            }
        });
        tabs.addView(tabHome);
        tabMore = new Button(this);
        tabMore.setText("MORE");
        tabMore.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tabMore.setLayoutParams(tabParams);
        tabMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = 1;
                showTab();
                refreshMoreTab();
            }
        });
        tabs.addView(tabMore);
        main.addView(tabs);

        homeContent = new LinearLayout(this);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeContent.setGravity(Gravity.CENTER);
        main.addView(homeContent);

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
        homeContent.addView(logoFrame);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusText.setGravity(Gravity.CENTER);
        homeContent.addView(statusText);

        if (!zeroTierInstalled) {
            TextView msg = new TextView(this);
            msg.setText("GRID0 needs ZeroTier to connect. Install ZeroTier One from the Play Store.");
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(16);
            msg.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            msg.setGravity(Gravity.CENTER);
            homeContent.addView(msg);

            TextView step1 = new TextView(this);
            step1.setText("Step 1");
            step1.setTextColor(Color.WHITE);
            step1.setTextSize(16);
            step1.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step1.setGravity(Gravity.CENTER);
            homeContent.addView(step1);

            Button install = new Button(this);
            install.setText("Get ZeroTier");
            install.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            install.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openZeroTierStore();
                }
            });
            homeContent.addView(install);
        } else {
            TextView step1 = new TextView(this);
            step1.setText("Step 1");
            step1.setTextColor(Color.WHITE);
            step1.setTextSize(16);
            step1.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step1.setGravity(Gravity.CENTER);
            homeContent.addView(step1);

            Button connect = new Button(this);
            connect.setText("Connect to GRID0 Network");
            connect.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            connect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connectToGrid0Network();
                }
            });
            homeContent.addView(connect);
        }

        TextView step2 = new TextView(this);
        step2.setText("Step 2");
        step2.setTextColor(Color.WHITE);
        step2.setTextSize(16);
        step2.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        step2.setGravity(Gravity.CENTER);
        homeContent.addView(step2);

        proxyButton = new Button(this);
        proxyButton.setText("Start Proxy");
        proxyButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        proxyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleProxy();
            }
        });
        homeContent.addView(proxyButton);

        LinearLayout social = new LinearLayout(this);
        social.setOrientation(LinearLayout.HORIZONTAL);
        Button discord = new Button(this);
        discord.setText("\uD83C\uDFAE Discord");
        discord.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams socialParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        discord.setLayoutParams(socialParams);
        discord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/splatfest"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(web);
            }
        });
        social.addView(discord);
        Button support = new Button(this);
        support.setText("Support Us");
        support.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        support.setLayoutParams(socialParams);
        support.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });
        social.addView(support);
        homeContent.addView(social);

        moreContent = new LinearLayout(this);
        moreContent.setOrientation(LinearLayout.VERTICAL);
        moreContent.setGravity(Gravity.CENTER);
        moreContent.setVisibility(View.GONE);
        main.addView(moreContent);

        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(true);
        moreContent.addView(table);
        moreValues.clear();
        addMoreRow(table, "ZeroTier installed", "zt_installed");
        addMoreRow(table, "ZeroTier IP", "zt_ip");
        addMoreRow(table, "Phone IP", "phone_ip");
        addMoreRow(table, "Client isolation", "isolation");
        addMoreRow(table, "ZeroTier status", "zt_status");
        addMoreRow(table, "GRID0 network", "grid0_network");
        addMoreRow(table, "Network ID", "network_id");

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshMoreTab();
            }
        });
        moreContent.addView(refresh);

        GradientDrawable logBorder = new GradientDrawable();
        logBorder.setColor(Color.BLACK);
        int borderWidth = (int) (2 * getResources().getDisplayMetrics().density);
        logBorder.setStroke(borderWidth, Color.WHITE);
        logScroll = new ScrollView(this);
        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (140 * getResources().getDisplayMetrics().density));
        logParams.gravity = Gravity.BOTTOM;
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        logParams.leftMargin = margin;
        logParams.rightMargin = margin;
        logParams.bottomMargin = margin;
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
        if (proxyRunning && !logText.isEmpty()) {
            logView.setText(logText);
        }
        showTab();
        updateProxyUI();
    }

    private void addMoreRow(TableLayout table, String label, String key) {
        TableRow row = new TableRow(this);
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(Color.WHITE);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        labelView.setTextSize(14);
        row.addView(labelView);
        TextView valueView = new TextView(this);
        valueView.setText("");
        valueView.setTextColor(Color.WHITE);
        valueView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        valueView.setTextSize(14);
        row.addView(valueView);
        table.addView(row);
        moreValues.put(key, valueView);
    }

    private void showTab() {
        if (homeContent == null || moreContent == null) {
            return;
        }
        if (currentTab == 0) {
            homeContent.setVisibility(View.VISIBLE);
            moreContent.setVisibility(View.GONE);
            if (tabHome != null) {
                tabHome.setAlpha(1.0f);
            }
            if (tabMore != null) {
                tabMore.setAlpha(0.5f);
            }
        } else {
            homeContent.setVisibility(View.GONE);
            moreContent.setVisibility(View.VISIBLE);
            if (tabHome != null) {
                tabHome.setAlpha(0.5f);
            }
            if (tabMore != null) {
                tabMore.setAlpha(1.0f);
            }
        }
    }

    private void refreshMoreTab() {
        setMoreValue("zt_installed", isZeroTierInstalled() ? "Yes" : "No");
        String ztIp = getZeroTierIp();
        setMoreValue("zt_ip", ztIp.equals("not found") ? "not connected" : ztIp);
        setMoreValue("phone_ip", getPhoneWifiIp());
        setMoreValue("isolation", hasClientIsolation() ? "Detected" : "Not detected");
        setMoreValue("zt_status", isVpnConnected() ? "Connected" : "Not connected");
        setMoreValue("grid0_network", isGrid0Connected() ? "Connected" : "Not connected");
        setMoreValue("network_id", GRID0_NETWORK_ID);
    }

    private void setMoreValue(String key, String value) {
        TextView view = moreValues.get(key);
        if (view != null) {
            view.setText(value);
        }
    }

    private void toggleProxy() {
        if (!proxyRunning) {
            if (!isGrid0Connected()) {
                Toast.makeText(this, "Connect to the GRID0 network first", Toast.LENGTH_LONG).show();
                return;
            }
            logText = "Checking client isolation...\n";
            if (hasClientIsolation()) {
                logText += "ERROR: Your wifi has client isolation\n";
                isolationError = true;
                proxyRunning = true;
                if (logView != null) {
                    logView.setText(logText);
                }
                updateProxyUI();
                return;
            }
            logText += "Client isolation: not detected\n";
            String phoneIp = getPhoneWifiIp();
            String ztIp = getZeroTierIp();
            logText += "PHONE_IP = " + phoneIp + "\nZT_IP = " + ztIp + "\n";
            isolationError = false;
            proxyRunning = true;
            if (logView != null) {
                logView.setText(logText);
            }
            updateProxyUI();
        } else {
            proxyRunning = false;
            isolationError = false;
            logText = "";
            if (logView != null) {
                logView.setText("");
            }
            updateProxyUI();
        }
    }

    private boolean hasClientIsolation() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            int others = 0;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("IP address")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) {
                    continue;
                }
                String ip = parts[0];
                String mac = parts[3];
                if (mac.equals("00:00:00:00:00:00")) {
                    continue;
                }
                if (isGatewayIp(ip)) {
                    continue;
                }
                others++;
            }
            reader.close();
            return others == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isGatewayIp(String ip) {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int gateway = wifi.getDhcpInfo().gateway;
                if (gateway != 0) {
                    String gatewayIp = (gateway & 0xff) + "." + ((gateway >> 8) & 0xff) + "." + ((gateway >> 16) & 0xff) + "." + ((gateway >> 24) & 0xff);
                    return ip.equals(gatewayIp);
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    private boolean isGrid0Connected() {
        String ztIp = getZeroTierIp();
        return !ztIp.equals("not found") && ztIp.startsWith("10.147.");
    }

    private boolean isVpnConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
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
        boolean running = proxyRunning && !isolationError;
        if (proxyButton != null) {
            proxyButton.setText(proxyRunning ? "Stop Proxy" : "Start Proxy");
            boolean canStart = proxyRunning || isGrid0Connected();
            proxyButton.setEnabled(canStart);
            proxyButton.setAlpha(canStart ? 1.0f : 0.5f);
        }
        if (logScroll != null) {
            logScroll.setVisibility(proxyRunning ? View.VISIBLE : View.GONE);
        }
        if (statusText != null) {
            statusText.setText(running ? "ON" : "OFF");
            statusText.setTextColor(running ? Color.RED : Color.GRAY);
        }
        if (logoImage != null) {
            if (running) {
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
            if (running) {
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
