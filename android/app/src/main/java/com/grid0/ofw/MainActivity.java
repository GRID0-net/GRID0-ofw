package com.grid0.ofw;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String ZEROTIER_PACKAGE = "com.zerotier.one";
    private static final String GRID0_NETWORK_ID = "8bd5124fd68185ec";
    private boolean zeroTierInstalled = false;
    private boolean proxyRunning = false;
    private String logText = "";
    private String isolationResult = "Not checked";
    private int currentTab = 0;
    private ImageView logoImage;
    private PixelRippleView pixelRippleView;
    private ValueAnimator rippleAnimator;
    private ValueAnimator waveAnimator;
    private ValueAnimator saturationAnimator;
    private ValueAnimator borderAnimator;
    private TextView statusText;
    private FrameLayout messageSlot;
    private TextView step1Label;
    private Button step1Button;
    private TextView step2Label;
    private ScrollView logScroll;
    private TextView logView;
    private Button proxyButton;
    private TextView tabHome;
    private TextView tabMore;
    private LinearLayout homeContent;
    private LinearLayout moreContent;

    private Map<String, TextView> moreValues = new HashMap<>();
    private Button checkIsolationButton;
    private TextView isolationResultView;

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

    private Button makeButton(String label, int widthDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.BLACK);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFDDDDDD);
        bg.setCornerRadius((int) (2 * getResources().getDisplayMetrics().density));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                (int) (widthDp * getResources().getDisplayMetrics().density),
                (int) (48 * getResources().getDisplayMetrics().density));
        b.setLayoutParams(p);
        return b;
    }

    private TextView makeTab(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setAllCaps(false);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                (int) (152 * getResources().getDisplayMetrics().density),
                (int) (40 * getResources().getDisplayMetrics().density));
        p.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(p);
        t.setClickable(true);
        return t;
    }

    private void styleTab(TextView t, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? Color.BLACK : 0xFFDDDDDD);
        bg.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        t.setBackground(bg);
        t.setTextColor(active ? Color.WHITE : Color.BLACK);
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

            if (v == null || !v.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(25);
            }
        } catch (Exception e) {
        }
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
        mainParams.topMargin = (int) (24 * getResources().getDisplayMetrics().density);
        main.setLayoutParams(mainParams);
        root.addView(main);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (48 * getResources().getDisplayMetrics().density));
        tabs.setLayoutParams(tabsParams);
        tabHome = makeTab("HOME");
        ((LinearLayout.LayoutParams) tabHome.getLayoutParams()).rightMargin =
                (int) (8 * getResources().getDisplayMetrics().density);
        tabHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = 0;

                showTab();
            }
        });
        tabs.addView(tabHome);
        tabMore = makeTab("MORE");
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

        int frameSize = (int) (228 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(frameSize, frameSize);
        frameParams.gravity = Gravity.CENTER;
        logoFrame.setLayoutParams(frameParams);

        logoImage = new ImageView(this);
        logoImage.setImageResource(R.drawable.logo);

        int logoSize = (int) (220 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(logoSize, logoSize);
        logoParams.gravity = Gravity.CENTER;
        logoImage.setLayoutParams(logoParams);
        logoImage.setClickable(true);
        logoImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleProxy();
            }
        });
        logoFrame.addView(logoImage);

        pixelRippleView = new PixelRippleView(this);
        int rippleSize = (int) (360 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams rippleParams = new FrameLayout.LayoutParams(rippleSize, rippleSize);
        rippleParams.gravity = Gravity.CENTER;
        pixelRippleView.setLayoutParams(rippleParams);
        pixelRippleView.setClickable(false);
        logoFrame.addView(pixelRippleView);
        logoFrame.setClipChildren(false);
        homeContent.setClipChildren(false);
        main.setClipChildren(false);
        root.setClipChildren(false);
        homeContent.addView(logoFrame);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        statusText.setLayoutParams(statusParams);
        homeContent.addView(statusText);

        messageSlot = new FrameLayout(this);
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (48 * getResources().getDisplayMetrics().density));
        messageSlot.setLayoutParams(slotParams);
        homeContent.addView(messageSlot);

        if (!zeroTierInstalled) {
            TextView msg = new TextView(this);
            msg.setText("GRID0 needs ZeroTier to connect. Install ZeroTier One from the Play Store.");
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(16);
            msg.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            msg.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams msgParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            msgParams.gravity = Gravity.CENTER;
            msg.setLayoutParams(msgParams);
            messageSlot.addView(msg);

            step1Label = new TextView(this);
            step1Label.setText("Step 1");
            step1Label.setTextColor(Color.WHITE);
            step1Label.setTextSize(16);
            step1Label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step1Label.setGravity(Gravity.CENTER);
            homeContent.addView(step1Label);

            step1Button = makeButton("Get ZeroTier", 140);
            step1Button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openZeroTierStore();
                }
            });
            homeContent.addView(step1Button);
        } else {
            step1Label = new TextView(this);
            step1Label.setText("Step 1");
            step1Label.setTextColor(Color.WHITE);
            step1Label.setTextSize(16);
            step1Label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            step1Label.setGravity(Gravity.CENTER);
            homeContent.addView(step1Label);

            step1Button = makeButton("Connect to GRID0 Network", 250);
            step1Button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connectToGrid0Network();
                }
            });
            homeContent.addView(step1Button);
        }

        step2Label = new TextView(this);
        step2Label.setText("Step 2");
        step2Label.setTextColor(Color.WHITE);
        step2Label.setTextSize(16);
        step2Label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        step2Label.setGravity(Gravity.CENTER);
        homeContent.addView(step2Label);

        proxyButton = makeButton("Start Proxy", 140);
        proxyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleProxy();
            }
        });
        homeContent.addView(proxyButton);

        moreContent = new LinearLayout(this);
        moreContent.setOrientation(LinearLayout.VERTICAL);
        moreContent.setGravity(Gravity.CENTER);
        moreContent.setVisibility(View.GONE);
        main.addView(moreContent);

        LinearLayout tableBox = new LinearLayout(this);
        tableBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable tableBorder = new GradientDrawable();
        tableBorder.setColor(Color.TRANSPARENT);
        tableBorder.setStroke((int) (1 * getResources().getDisplayMetrics().density), Color.LTGRAY);
        tableBorder.setCornerRadius((int) (8 * getResources().getDisplayMetrics().density));
        tableBox.setBackground(tableBorder);
        int boxPad = (int) (4 * getResources().getDisplayMetrics().density);
        tableBox.setPadding(boxPad, boxPad, boxPad, boxPad);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int boxMargin = (int) (16 * getResources().getDisplayMetrics().density);
        boxParams.leftMargin = boxMargin;
        boxParams.rightMargin = boxMargin;
        tableBox.setLayoutParams(boxParams);

        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tableBox.addView(table);
        moreContent.addView(tableBox);
        moreValues.clear();

        addMoreRow(table, "ZeroTier installed", "zt_installed");

        addMoreRow(table, "ZeroTier IP", "zt_ip");

        addMoreRow(table, "Phone IP", "phone_ip");

        addMoreRow(table, "Client isolation", "isolation");

        addMoreRow(table, "ZeroTier status", "zt_status");

        addMoreRow(table, "GRID0 network", "grid0_network");

        addMoreRow(table, "Network ID", "network_id");

        Button refresh = makeButton("Refresh", 110);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshMoreTab();
            }
        });
        moreContent.addView(refresh);

        checkIsolationButton = makeButton("Check Client Isolation", 220);
        checkIsolationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkClientIsolation();
            }
        });
        moreContent.addView(checkIsolationButton);

        TextView isolationHelp = new TextView(this);
        isolationHelp.setText("Scans this Wi-Fi network for any other device. If one answers, client isolation is off.");
        isolationHelp.setTextColor(Color.WHITE);
        isolationHelp.setTextSize(14);
        isolationHelp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        isolationHelp.setGravity(Gravity.CENTER);
        moreContent.addView(isolationHelp);

        isolationResultView = new TextView(this);
        isolationResultView.setText(isolationResult);
        isolationResultView.setTextColor(Color.WHITE);
        isolationResultView.setTextSize(14);
        isolationResultView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        isolationResultView.setGravity(Gravity.CENTER);
        moreContent.addView(isolationResultView);

        LinearLayout social = new LinearLayout(this);
        social.setOrientation(LinearLayout.HORIZONTAL);

        Button discord = new Button(this);
        discord.setText("Discord");
        discord.setAllCaps(false);
        discord.setTextSize(14);
        discord.setTextColor(Color.WHITE);
        discord.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        GradientDrawable discordBg = new GradientDrawable();
        discordBg.setColor(0xFF5865F2);
        discordBg.setCornerRadius((int) (4 * getResources().getDisplayMetrics().density));
        discord.setBackground(discordBg);
        discord.setStateListAnimator(null);

        try {
            Drawable discordIcon = getDrawable(R.drawable.ic_discord);

            if (discordIcon != null) {
                int iconSize = (int) (24 * getResources().getDisplayMetrics().density);
                discordIcon.setBounds(0, 0, iconSize, iconSize);
                discord.setCompoundDrawables(discordIcon, null, null, null);
                discord.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
            }
        } catch (Exception e) {
        }

        LinearLayout.LayoutParams socialParams = new LinearLayout.LayoutParams(
                (int) (152 * getResources().getDisplayMetrics().density),
                (int) (48 * getResources().getDisplayMetrics().density));
        socialParams.rightMargin = (int) (4 * getResources().getDisplayMetrics().density);
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

        Button support = makeButton("Support Us", 152);
        ((LinearLayout.LayoutParams) support.getLayoutParams()).leftMargin =
                (int) (4 * getResources().getDisplayMetrics().density);
        support.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });
        social.addView(support);
        moreContent.addView(social);

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
                styleTab(tabHome, true);
            }

            if (tabMore != null) {
                styleTab(tabMore, false);
            }
        } else {
            homeContent.setVisibility(View.GONE);
            moreContent.setVisibility(View.VISIBLE);

            if (tabHome != null) {
                styleTab(tabHome, false);
            }

            if (tabMore != null) {
                styleTab(tabMore, true);
            }
        }
    }

    private void refreshMoreTab() {
        setMoreValue("zt_installed", isZeroTierInstalled() ? "Yes" : "No");

        String ztIp = getZeroTierIp();

        setMoreValue("zt_ip", ztIp.equals("not found") ? "not connected" : ztIp);

        setMoreValue("phone_ip", getPhoneWifiIp());

        setMoreValue("isolation", isolationResult);

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

    private void checkClientIsolation() {
        if (checkIsolationButton != null) {
            checkIsolationButton.setEnabled(false);
            checkIsolationButton.setText("Checking...");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = scanForOtherDevices();
                isolationResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isolationResultView != null) {
                            isolationResultView.setText(result);
                        }

                        setMoreValue("isolation", result);

                        if (checkIsolationButton != null) {
                            checkIsolationButton.setText("Check Client Isolation");
                            checkIsolationButton.setEnabled(proxyRunning);
                            checkIsolationButton.setAlpha(proxyRunning ? 1.0f : 0.5f);
                        }
                    }
                });
            }
        }).start();
    }

    private String scanForOtherDevices() {
        int phoneIp = 0;
        int mask = 0;
        int gatewayIp = 0;

        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            if (wifi == null) return "Wi-Fi is not connected";

            DhcpInfo dhcp = wifi.getDhcpInfo();

            if (dhcp == null || dhcp.ipAddress == 0 || dhcp.netmask == 0) return "Wi-Fi is not connected";

            phoneIp = Integer.reverseBytes(dhcp.ipAddress);
            mask = Integer.reverseBytes(dhcp.netmask);
            gatewayIp = Integer.reverseBytes(dhcp.gateway);
        } catch (Exception e) {
            return "Wi-Fi is not connected";
        }

        int network = phoneIp & mask;
        int broadcast = network | ~mask;
        int total = broadcast - network - 1;
        List<Integer> hosts = new ArrayList<>();

        if (total > 0 && total <= 256) {
            for (int h = network + 1; h < broadcast; h++) {
                if (h == phoneIp || h == gatewayIp) continue;
                hosts.add(h);
            }
        } else {
            int net24 = phoneIp & 0xFFFFFF00;
            for (int last = 1; last < 255; last++) {
                int h = net24 | last;
                if (h == phoneIp || h == gatewayIp) continue;
                hosts.add(h);
            }
        }

        final DatagramSocket socket;

        try {
            socket = new DatagramSocket();
            bindToWifi(socket);
        } catch (Exception e) {
            return "Could not open a socket";
        }

        try {
            final byte[] ping = new byte[]{0};
            ExecutorService pool = Executors.newFixedThreadPool(32);

            for (final int host : hosts) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            socket.send(new DatagramPacket(ping, 1, InetAddress.getByAddress(intToIpBytes(host)), 5353));
                        } catch (Exception e) {
                        }
                    }
                });
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            Thread.sleep(2000);
        } catch (Exception e) {
        } finally {
            socket.close();
        }

        int found = countArpDevices(phoneIp, gatewayIp);

        if (found > 0) {
            return "Client Isolation is Disabled, you're good to go";
        }

        return "No other device answered. Isolation may be on, or nothing else is awake.";
    }

    private void bindToWifi(DatagramSocket socket) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            if (cm == null) return;

            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);

                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    network.bindSocket(socket);
                    return;
                }
            }
        } catch (Exception e) {
        }
    }

    private int countArpDevices(int phoneIp, int gatewayIp) {
        int found = 0;

        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"));

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("IP address")) {
                    continue;
                }

                String[] parts = line.trim().split("\\s+");

                if (parts.length < 4) {
                    continue;
                }

                String mac = parts[3].toUpperCase();

                if (mac.equals("00:00:00:00:00:00")) {
                    continue;
                }

                int ip = ipStringToInt(parts[0]);

                if (ip == phoneIp || ip == gatewayIp) {
                    continue;
                }

                found++;
            }

            reader.close();
        } catch (Exception e) {
        }

        return found;
    }

    private byte[] intToIpBytes(int ip) {
        return new byte[]{(byte) ((ip >> 24) & 0xff), (byte) ((ip >> 16) & 0xff), (byte) ((ip >> 8) & 0xff), (byte) (ip & 0xff)};
    }

    private int ipStringToInt(String ip) {
        try {
            String[] p = ip.split("\\.");
            return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16) | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
        } catch (Exception e) {
            return 0;
        }
    }

    private void toggleProxy() {
        buzz();

        if (!proxyRunning) {
            if (!isGrid0Connected()) {
                Toast.makeText(this, "Connect to the GRID0 network first", Toast.LENGTH_LONG).show();

                return;
            }

            String phoneIp = getPhoneWifiIp();

            String ztIp = getZeroTierIp();
            logText = "PHONE_IP = " + phoneIp + "\nZT_IP = " + ztIp + "\n";
            proxyRunning = true;

            if (logView != null) {
                logView.setText(logText);
            }

            updateProxyUI();
        } else {
            proxyRunning = false;
            logText = "";

            if (logView != null) {
                logView.setText("");
            }

            updateProxyUI();
        }
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

    private void startRipple() {
        if (pixelRippleView == null) return;
        stopRipple();
        pixelRippleView.reset();
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f);
        rippleAnimator.setDuration(9000);
        rippleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                long elapsed = (long) (t * 9000);
                for (int i = 0; i < 4; i++) {
                    float p = Math.min(Math.max((elapsed - i * 500f) / 8000f, 0f), 1f);
                    pixelRippleView.setRingProgress(i, p);
                }
            }
        });
        rippleAnimator.start();
        waveAnimator = ValueAnimator.ofFloat(0f, 1f);
        waveAnimator.setDuration(2000);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pixelRippleView.setWavePhase((Float) a.getAnimatedValue());
            }
        });
        waveAnimator.start();
    }

    private void stopRipple() {
        if (rippleAnimator != null) {
            rippleAnimator.cancel();
            rippleAnimator = null;
        }
        if (waveAnimator != null) {
            waveAnimator.cancel();
            waveAnimator = null;
        }
        if (pixelRippleView != null) {
            pixelRippleView.reset();
        }
    }

    private static class PixelRippleView extends View {
        private static final int SIDES = 16;
        private static final int[] RING_COLORS = {0xFFFF0000, 0xFFFF0000, 0xFFD40000, 0xFF990000};
        private static final float[] RING_RADII_DP = {88f, 96f, 104f, 112f};
        private static final float BORDER_RADIUS_DP = 112f;
        private final Paint fillPaint = new Paint();
        private final Paint strokePaint = new Paint();
        private final float[] ringProgress = {0f, 0f, 0f, 0f};
        private float wavePhase = 0f;
        private float borderProgress = 0f;

        public PixelRippleView(Context context) {
            super(context);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setAntiAlias(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setAntiAlias(false);
        }

        public void reset() {
            for (int i = 0; i < 4; i++) ringProgress[i] = 0f;
            wavePhase = 0f;
            invalidate();
        }

        public void setRingProgress(int i, float p) {
            ringProgress[i] = p;
            invalidate();
        }

        public void setWavePhase(float phase) {
            wavePhase = phase;
            invalidate();
        }

        public float getBorderProgress() {
            return borderProgress;
        }

        public void setBorderProgress(float p) {
            borderProgress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float pulse = 1f + 0.02f * (float) Math.sin(wavePhase * 2f * Math.PI);
            strokePaint.setStrokeWidth(3f * density);
            strokePaint.setColor(lerpColor(0xFF808080, 0xFFFF0000, borderProgress));
            canvas.drawCircle(cx, cy, BORDER_RADIUS_DP * density * pulse, strokePaint);
            for (int i = 0; i < 4; i++) {
                float p = ringProgress[i];
                if (p <= 0f) continue;
                float grow = Math.min(p / 0.25f, 1f);
                float ease = 1f - (1f - grow) * (1f - grow);
                float r = RING_RADII_DP[i] * density * (0.7f + 0.3f * ease);
                fillPaint.setColor(RING_COLORS[i]);
                fillPaint.setAlpha((int) (0.9f * (1f - p) * 255));
                drawSquareRing(canvas, cx, cy, r, fillPaint);
            }
        }

        private void drawSquareRing(Canvas canvas, float cx, float cy, float r, Paint paint) {
            float density = getResources().getDisplayMetrics().density;
            float snap = 4f * density;
            float sq = 6f * density;
            float step = 12f * density;
            float[][] v = new float[SIDES][2];
            for (int k = 0; k < SIDES; k++) {
                double angle = Math.toRadians(-90 + k * (360.0 / SIDES));
                float wobble = 1f + 0.04f * (float) Math.sin(2f * Math.PI * (k / (float) SIDES + wavePhase));
                float vr = r * wobble;
                float x = cx + (float) (vr * Math.cos(angle));
                float y = cy + (float) (vr * Math.sin(angle));
                v[k][0] = Math.round(x / snap) * snap;
                v[k][1] = Math.round(y / snap) * snap;
            }
            for (int k = 0; k < SIDES; k++) {
                float x0 = v[k][0];
                float y0 = v[k][1];
                float x1 = v[(k + 1) % SIDES][0];
                float y1 = v[(k + 1) % SIDES][1];
                float len = (float) Math.hypot(x1 - x0, y1 - y0);
                int n = Math.max(1, (int) (len / step));
                for (int i = 0; i < n; i++) {
                    float t = (i + 0.5f) / n;
                    float x = Math.round((x0 + (x1 - x0) * t) / snap) * snap;
                    float y = Math.round((y0 + (y1 - y0) * t) / snap) * snap;
                    canvas.drawRect(x - sq / 2f, y - sq / 2f, x + sq / 2f, y + sq / 2f, paint);
                }
            }
        }

        private static int lerpColor(int from, int to, float t) {
            int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
            int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
            int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
            int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
            return Color.argb(a, r, g, b);
        }
    }

    private void updateProxyUI() {
        if (proxyButton != null) {
            proxyButton.setText(proxyRunning ? "Stop Proxy" : "Start Proxy");

            boolean canStart = proxyRunning || isGrid0Connected();
            proxyButton.setEnabled(canStart);
            proxyButton.setAlpha(canStart ? 1.0f : 0.5f);
        }

        int stepVis = proxyRunning ? View.GONE : View.VISIBLE;

        if (messageSlot != null) {
            messageSlot.setVisibility(stepVis);
        }

        if (step1Label != null) {
            step1Label.setVisibility(stepVis);
        }

        if (step1Button != null) {
            step1Button.setVisibility(stepVis);
        }

        if (step2Label != null) {
            step2Label.setVisibility(stepVis);
        }

        if (checkIsolationButton != null) {
            checkIsolationButton.setEnabled(proxyRunning);
            checkIsolationButton.setAlpha(proxyRunning ? 1.0f : 0.5f);
        }

        if (logScroll != null) {
            logScroll.setVisibility(proxyRunning ? View.VISIBLE : View.GONE);
        }

        if (statusText != null) {
            statusText.setText(proxyRunning ? "ON" : "OFF");
            statusText.setTextColor(proxyRunning ? Color.RED : Color.GRAY);
        }

        if (logoImage != null) {
            if (saturationAnimator != null) saturationAnimator.cancel();
            if (proxyRunning) {
                saturationAnimator = ValueAnimator.ofFloat(0f, 1f);
                saturationAnimator.setDuration(800);
                saturationAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        float s = (Float) a.getAnimatedValue();
                        ColorMatrix matrix = new ColorMatrix();
                        matrix.setSaturation(s);
                        logoImage.setColorFilter(new ColorMatrixColorFilter(matrix));
                    }
                });
                saturationAnimator.start();
                startRipple();
            } else {
                stopRipple();
                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(0);
                logoImage.setColorFilter(new ColorMatrixColorFilter(matrix));
            }
        }

        if (pixelRippleView != null) {
            if (borderAnimator != null) borderAnimator.cancel();
            borderAnimator = ValueAnimator.ofFloat(pixelRippleView.getBorderProgress(), proxyRunning ? 1f : 0f);
            borderAnimator.setDuration(800);
            borderAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pixelRippleView.setBorderProgress((Float) a.getAnimatedValue());
                }
            });
            borderAnimator.start();
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
