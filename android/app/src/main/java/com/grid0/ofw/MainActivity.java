package com.grid0.ofw;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.Switch;
import android.widget.ImageButton;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;
import com.chaquo.python.Python;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private String isolationDetail = "";
    private String switchIpResult = "Not detected";
    private String lastSwitchId = null;
    private int switchSubnet = -1;
    private boolean lanScanRunning = false;
    private long lastLanScanMs = 0;
    private int currentTab = 0;
    private ImageView logoImage;
    private PixelRippleView pixelRippleView;
    private ValueAnimator waveAnimator;
    private ValueAnimator spinAnimator;
    private ValueAnimator saturationAnimator;
    private ValueAnimator borderAnimator;
    private TextView statusText;
    private TextView switchDetectedView;
    private TextView step1Label;
    private Button step1Button;
    private ImageView step1Check;
    private TextView step2Label;
    private Button step2Button;
    private ImageView step2Check;
    private TextView step3Label;
    private Button step3Button;
    private Bitmap checkBitmap;
    private ScrollView logScroll;
    private TextView logView;
    private Switch detailedSwitch;
    private ImageButton copyLogButton;
    private FrameLayout consoleBox;
    private LinearLayout socialRow;
    private Button discordBtn;
    private Button supportBtn;
    private LinearLayout toggleRow;
    private static boolean updateChecked = false;
    private TextView tabHome;
    private TextView tabMore;
    private LinearLayout homeContent;
    private LinearLayout moreContent;
    private Switch tutorialSwitch;
    private FrameLayout logoFrame;
    private View installMsgView;
    private View step1Row;
    private View step2Row;
    private View step3Row;

    private Map<String, TextView> moreValues = new HashMap<>();


    private final Handler switchCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable switchCheckRunnable = new Runnable() {
        @Override
        public void run() {
            updateSwitchDetected();
            switchCheckHandler.postDelayed(this, 15000);
        }
    };

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        zeroTierInstalled = isZeroTierInstalled();

        if (!getPreferences(MODE_PRIVATE).getBoolean("tutorial_default_v2", false)) {
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("tutorial_on", true)
                    .putBoolean("tutorial_default_v2", true)
                    .apply();
        }

        buildLayout();
        switchCheckHandler.postDelayed(switchCheckRunnable, 15000);
        checkForUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();

        proxyRunning = ProxyService.running;

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
        bg.setCornerRadius((int) (10 * getResources().getDisplayMetrics().density));
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
        bg.setColor(active ? Color.BLACK : Color.TRANSPARENT);
        bg.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        t.setBackground(bg);
        t.setTextColor(active ? Color.WHITE : Color.BLACK);
    }

    private void setButtonFill(Button b, boolean light) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(light ? 0xFFDDDDDD : 0xFF5A5A5A);
        bg.setCornerRadius((int) (10 * getResources().getDisplayMetrics().density));
        b.setBackground(bg);
    }

    private TextView makeStepLabel(String label, int topMarginDp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) (topMarginDp * getResources().getDisplayMetrics().density);
        t.setLayoutParams(p);
        return t;
    }

    private FrameLayout makeStepRow(Button b) {
        float density = getResources().getDisplayMetrics().density;
        FrameLayout row = new FrameLayout(this);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                (int) (245 * density),
                (int) (48 * density));
        rowParams.gravity = Gravity.CENTER;
        rowParams.topMargin = (int) (8 * density);
        row.setLayoutParams(rowParams);
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
                (int) (245 * density),
                (int) (48 * density));
        b.setLayoutParams(btnParams);
        row.addView(b);
        return row;
    }

    private ImageView addCheckToRow(FrameLayout row) {
        float density = getResources().getDisplayMetrics().density;
        ImageView check = new ImageView(this);
        check.setImageBitmap(getCheckBitmap());
        int s = (int) (28 * density);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(s, s);
        p.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        p.rightMargin = (int) (12 * density);
        check.setLayoutParams(p);
        check.setVisibility(View.GONE);
        row.addView(check);
        return check;
    }

    private Bitmap getCheckBitmap() {
        if (checkBitmap != null) return checkBitmap;
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (28 * density);
        checkBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(checkBitmap);
        Paint circle = new Paint();
        circle.setAntiAlias(true);
        circle.setColor(Color.BLACK);
        c.drawCircle(size / 2f, size / 2f, size / 2f, circle);
        Paint tick = new Paint();
        tick.setAntiAlias(true);
        tick.setColor(Color.WHITE);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(2.5f * density);
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeJoin(Paint.Join.ROUND);
        Path p = new Path();
        p.moveTo(size * 0.30f, size * 0.52f);
        p.lineTo(size * 0.45f, size * 0.67f);
        p.lineTo(size * 0.72f, size * 0.35f);
        c.drawPath(p, tick);
        return checkBitmap;
    }

    private void updateSteps() {
        boolean installed = zeroTierInstalled;
        boolean connected = isGrid0Connected();
        if (step1Button != null) {
            setButtonFill(step1Button, !installed);
            step1Button.setEnabled(!installed);
        }
        if (step1Check != null) {
            step1Check.setVisibility(installed ? View.VISIBLE : View.GONE);
        }
        if (step2Button != null) {
            setButtonFill(step2Button, installed && !connected);
            step2Button.setEnabled(installed && !connected);
        }
        if (step2Check != null) {
            step2Check.setVisibility(connected ? View.VISIBLE : View.GONE);
        }
        if (step3Button != null) {
            setButtonFill(step3Button, connected);
            step3Button.setEnabled(connected);
            step3Button.setText(proxyRunning ? "Stop Proxy" : "Start Proxy");
        }
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
                FrameLayout.LayoutParams.MATCH_PARENT);
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
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0xFFDDDDDD);
        trackBg.setCornerRadius((int) (24 * getResources().getDisplayMetrics().density));
        trackBg.setStroke((int) (1 * getResources().getDisplayMetrics().density), Color.WHITE);
        tabs.setBackground(trackBg);
        int trackPad = (int) (4 * getResources().getDisplayMetrics().density);
        tabs.setPadding(trackPad, trackPad, trackPad, trackPad);
        tabHome = makeTab("PLAY");
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
        tabs.setElevation(8 * getResources().getDisplayMetrics().density);
        main.addView(tabs);

        homeContent = new LinearLayout(this);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeContent.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        homeParams.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        homeContent.setLayoutParams(homeParams);
        main.addView(homeContent);

        logoFrame = new FrameLayout(this);

        int frameSize = (int) (180 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(frameSize, frameSize);
        frameParams.gravity = Gravity.CENTER;
        frameParams.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        logoFrame.setLayoutParams(frameParams);

        logoImage = new ImageView(this);
        logoImage.setImageResource(R.drawable.logo);

        int logoSize = (int) (184 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(logoSize, logoSize);
        logoParams.gravity = Gravity.CENTER;
        logoImage.setLayoutParams(logoParams);
        logoImage.setClickable(true);
        logoImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tutorialSwitch != null && !tutorialSwitch.isChecked() && !proxyRunning) {
                    if (!zeroTierInstalled) {
                        Toast.makeText(MainActivity.this, "ZeroTier isn't installed", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isGrid0Connected()) {
                        Toast.makeText(MainActivity.this, "You're not connected to GRID0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                toggleProxy();
            }
        });
        pixelRippleView = new PixelRippleView(this);
        pixelRippleView.setClickable(false);
        pixelRippleView.setVisibility(View.GONE);
        logoFrame.addView(logoImage);
        logoFrame.setClipChildren(false);
        homeContent.setClipChildren(false);
        main.setClipChildren(false);
        root.setClipChildren(false);
        homeContent.addView(logoFrame);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusText.setGravity(Gravity.CENTER);
        statusText.setLetterSpacing(0.1f);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.BLACK);
        pillBg.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        pillBg.setStroke((int) (1 * getResources().getDisplayMetrics().density), 0xFF3A3A3A);
        statusText.setBackground(pillBg);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                (int) (80 * getResources().getDisplayMetrics().density),
                (int) (24 * getResources().getDisplayMetrics().density));
        statusParams.gravity = Gravity.CENTER;
        statusParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        statusText.setLayoutParams(statusParams);
        homeContent.addView(statusText);

        switchDetectedView = new TextView(this);
        switchDetectedView.setTextSize(14);
        switchDetectedView.setTextColor(Color.WHITE);
        switchDetectedView.setGravity(Gravity.CENTER);
        GradientDrawable detectedPillBg = new GradientDrawable();
        detectedPillBg.setColor(0xFF2E7D32);
        detectedPillBg.setCornerRadius(999);
        switchDetectedView.setBackground(detectedPillBg);
        int pillPadX = (int) (14 * getResources().getDisplayMetrics().density);
        int pillPadY = (int) (6 * getResources().getDisplayMetrics().density);
        switchDetectedView.setPadding(pillPadX, pillPadY, pillPadX, pillPadY);
        LinearLayout.LayoutParams sdParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sdParams.gravity = Gravity.CENTER_HORIZONTAL;
        sdParams.topMargin = (int) (32 * getResources().getDisplayMetrics().density);
        switchDetectedView.setLayoutParams(sdParams);
        switchDetectedView.setVisibility(View.GONE);
        homeContent.addView(switchDetectedView);

        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (20 * getResources().getDisplayMetrics().density));
        spacer.setLayoutParams(spacerParams);
        homeContent.addView(spacer);

        if (!zeroTierInstalled) {
            TextView msg = new TextView(this);
            msg.setText("GRID0 needs ZeroTier to connect. Install ZeroTier One from the Play Store.");
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(16);
            msg.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            msg.setGravity(Gravity.CENTER);
            homeContent.addView(msg);
            installMsgView = msg;
        }

        step1Label = makeStepLabel("Step 1", 8);
        homeContent.addView(step1Label);

        step1Button = makeButton("Get ZeroTier", 245);
        step1Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openZeroTierStore();
            }
        });
        step1Row = makeStepRow(step1Button);
        step1Check = addCheckToRow((FrameLayout) step1Row);
        homeContent.addView(step1Row);

        step2Label = makeStepLabel("Step 2", 16);
        homeContent.addView(step2Label);

        step2Button = makeButton("Connect to GRID0", 245);
        step2Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectToGrid0Network();
            }
        });
        step2Row = makeStepRow(step2Button);
        step2Check = addCheckToRow((FrameLayout) step2Row);
        homeContent.addView(step2Row);

        step3Label = makeStepLabel("Step 3", 16);
        homeContent.addView(step3Label);

        step3Button = makeButton("Start Proxy", 245);
        step3Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleProxy();
            }
        });
        step3Row = makeStepRow(step3Button);
        homeContent.addView(step3Row);

        moreContent = new LinearLayout(this);
        moreContent.setOrientation(LinearLayout.VERTICAL);
        moreContent.setGravity(Gravity.CENTER);
        moreContent.setVisibility(View.GONE);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        moreContent.setLayoutParams(moreParams);
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
        boxParams.topMargin = (int) (2 * getResources().getDisplayMetrics().density);
        tableBox.setLayoutParams(boxParams);

        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tableBox.addView(table);
        ImageButton reloadBtn = new ImageButton(this);
        reloadBtn.setImageResource(android.R.drawable.ic_popup_sync);
        reloadBtn.setBackgroundColor(0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(96, 64);
        rlp.gravity = 5;
        rlp.topMargin = (int) (-20 * getResources().getDisplayMetrics().density);
        rlp.rightMargin = (int) (16 * getResources().getDisplayMetrics().density);
        reloadBtn.setLayoutParams(rlp);
        reloadBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.animate().rotationBy(180f).setDuration(300).start();
                refreshMoreTab();
            }
        });
        TextView versionView = new TextView(this);
        try {
            versionView.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            versionView.setText("");
        }
        versionView.setTextColor(0xFFAAAAAA);
        versionView.setTextSize(12);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        versionParams.leftMargin = (int) (16 * getResources().getDisplayMetrics().density);
        versionView.setLayoutParams(versionParams);
        versionView.setGravity(Gravity.START);
        moreContent.addView(versionView);
        moreContent.addView(reloadBtn);
        moreContent.addView(tableBox);
        moreValues.clear();

        addMoreRow(table, "ZeroTier Installed", "zt_installed");

        addMoreRow(table, "ZeroTier IP", "zt_ip");

        addMoreRow(table, "Phone IP", "phone_ip");

        addMoreRow(table, "Switch IP", "switch_ip");

        addMoreRow(table, "Client Isolation", "isolation");

        addMoreRow(table, "Wi-Fi Status", "wifi_status");

        addMoreRow(table, "ZeroTier Status", "zt_status");

        addMoreRow(table, "GRID0 Status", "grid0_network");

        addMoreRow(table, "Network ID", "network_id");



        socialRow = new LinearLayout(this);
        socialRow.setOrientation(LinearLayout.VERTICAL);
        socialRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = 108;
        socialRow.setLayoutParams(slp);

        discordBtn = new Button(this);
        discordBtn.setText("Join our Discord");
        discordBtn.setAllCaps(false);
        discordBtn.setTextSize(14);
        discordBtn.setTextColor(Color.WHITE);
        discordBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        GradientDrawable discordBg = new GradientDrawable();
        discordBg.setColor(0xFF5865F2);
        discordBg.setCornerRadius((int) (10 * getResources().getDisplayMetrics().density));
        discordBtn.setBackground(discordBg);
        discordBtn.setStateListAnimator(null);

        try {
            Drawable discordIcon = getDrawable(R.drawable.ic_discord);

            if (discordIcon != null) {
                int iconSize = (int) (24 * getResources().getDisplayMetrics().density);
                discordIcon.setBounds(0, 0, iconSize, iconSize);
                discordBtn.setCompoundDrawables(discordIcon, null, null, null);
                discordBtn.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
            }
        } catch (Exception e) {
        }

        LinearLayout.LayoutParams socialParams = new LinearLayout.LayoutParams(
                (int) (272 * getResources().getDisplayMetrics().density),
                (int) (48 * getResources().getDisplayMetrics().density));
        socialParams.rightMargin = 0;
        discordBtn.setLayoutParams(socialParams);
        discordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/splatfest"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(web);
            }
        });
        socialRow.addView(discordBtn);

        supportBtn = new Button(this);
        supportBtn.setText("Support Us");
        supportBtn.setAllCaps(false);
        supportBtn.setTextSize(14);
        supportBtn.setTextColor(Color.BLACK);
        supportBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable supportBg = new GradientDrawable();
        supportBg.setColor(0xFFFFFFFF);
        supportBg.setCornerRadius((int) (10 * getResources().getDisplayMetrics().density));
        supportBtn.setBackground(supportBg);
        supportBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams supportParams = new LinearLayout.LayoutParams(
                (int) (272 * getResources().getDisplayMetrics().density),
                (int) (48 * getResources().getDisplayMetrics().density));
        supportParams.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        supportBtn.setLayoutParams(supportParams);
        try {
            Drawable githubIcon = getDrawable(R.drawable.ic_github);
            if (githubIcon != null) {
                int iconSize = (int) (24 * getResources().getDisplayMetrics().density);
                githubIcon.setBounds(0, 0, iconSize, iconSize);
                supportBtn.setCompoundDrawables(githubIcon, null, null, null);
                supportBtn.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
            }
        } catch (Exception e) {
        }
        supportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GRID0-net/GRID0-ofw"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(web);
            }
        });
        socialRow.addView(supportBtn);
        moreContent.addView(socialRow);

        toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.gravity = Gravity.CENTER_HORIZONTAL;
        rowParams.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        toggleRow.setLayoutParams(rowParams);

        TextView toggleLabel = new TextView(this);
        toggleLabel.setText("Tutorial");
        toggleLabel.setTextColor(Color.WHITE);
        toggleLabel.setTextSize(14);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
        toggleLabel.setLayoutParams(labelParams);
        toggleRow.addView(toggleLabel);

        tutorialSwitch = new Switch(this);
        tutorialSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("tutorial_on", true));
        int[][] swStates = new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} };
        tutorialSwitch.setTrackTintList(new ColorStateList(swStates, new int[] { 0xFFFF0000, 0xFF555555 }));
        tutorialSwitch.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        tutorialSwitch.setScaleX(0.85f);
        tutorialSwitch.setScaleY(0.85f);
        tutorialSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("tutorial_on", on).apply();
                updateTutorialUI();
            }
        });
        toggleRow.addView(tutorialSwitch);

        TextView detailedLabel = new TextView(this);
        detailedLabel.setText("Detailed logging");
        detailedLabel.setTextColor(Color.WHITE);
        detailedLabel.setTextSize(14);
        LinearLayout.LayoutParams detailedLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailedLabelParams.leftMargin = (int) (20 * getResources().getDisplayMetrics().density);
        detailedLabelParams.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
        detailedLabel.setLayoutParams(detailedLabelParams);
        toggleRow.addView(detailedLabel);

        detailedSwitch = new Switch(this);
        detailedSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("detailed_log", false));
        detailedSwitch.setTrackTintList(new ColorStateList(swStates, new int[] { 0xFFFF0000, 0xFF555555 }));
        detailedSwitch.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        detailedSwitch.setScaleX(0.85f);
        detailedSwitch.setScaleY(0.85f);
        detailedSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("detailed_log", on).apply();
            }
        });
        toggleRow.addView(detailedSwitch);
        moreContent.addView(toggleRow);

        GradientDrawable logBorder = new GradientDrawable();
        logBorder.setColor(Color.BLACK);

        int borderWidth = (int) (2 * getResources().getDisplayMetrics().density);
        logBorder.setStroke(borderWidth, Color.WHITE);
        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        logScroll.setBackground(logBorder);
        logView = new TextView(this);
        logView.setText("");
        logView.setTextColor(Color.WHITE);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);

        int logPad = (int) (8 * getResources().getDisplayMetrics().density);
        logView.setPadding(logPad, logPad, logPad, logPad);
        logScroll.addView(logView);

        consoleBox = new FrameLayout(this);
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (250 * getResources().getDisplayMetrics().density));

        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        consoleParams.leftMargin = margin;
        consoleParams.rightMargin = margin;
        consoleParams.topMargin = margin;
        consoleBox.setLayoutParams(consoleParams);
        consoleBox.setVisibility(View.GONE);
        consoleBox.addView(logScroll);

        copyLogButton = new ImageButton(this);
        copyLogButton.setImageResource(R.drawable.ic_copy);
        copyLogButton.setBackgroundColor(0);
        int copyPad = (int) (6 * getResources().getDisplayMetrics().density);
        copyLogButton.setPadding(copyPad, copyPad, copyPad, copyPad);
        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(
                (int) (36 * getResources().getDisplayMetrics().density),
                (int) (36 * getResources().getDisplayMetrics().density));
        copyParams.gravity = Gravity.TOP | Gravity.END;
        copyParams.topMargin = copyPad;
        copyParams.rightMargin = copyPad;
        copyLogButton.setLayoutParams(copyParams);
        copyLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                String text = logView != null ? logView.getText().toString() : "";

                if (clipboard != null && !text.isEmpty()) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("GRID0 proxy log", text));
                    Toast.makeText(MainActivity.this, "Log copied", Toast.LENGTH_SHORT).show();
                }
            }
        });
        consoleBox.addView(copyLogButton);
        moreContent.addView(consoleBox);

        setContentView(root);

        FrameLayout.LayoutParams rippleOverlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        pixelRippleView.setLayoutParams(rippleOverlayParams);
        pixelRippleView.setVisibility(View.VISIBLE);
        root.addView(pixelRippleView, 0);

        logoFrame.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int[] loc = new int[2];
                logoFrame.getLocationOnScreen(loc);
                float centerY = loc[1] + logoFrame.getHeight() / 2f;
                int[] rootLoc = new int[2];
                root.getLocationOnScreen(rootLoc);
                pixelRippleView.setCenterY(centerY - rootLoc[1]);
            }
        });

        if (proxyRunning) {
            logView.setText(logText);
            refreshProxyLog();
            startLogRefresh();
        }

        showTab();

        updateProxyUI();

        updateTutorialUI();
    }

    private void updateTutorialUI() {
        boolean tutorial = tutorialSwitch == null || tutorialSwitch.isChecked();
        int vis = tutorial ? View.VISIBLE : View.GONE;
        if (installMsgView != null) installMsgView.setVisibility(vis);
        if (step1Label != null) step1Label.setVisibility(vis);
        if (step1Row != null) step1Row.setVisibility(vis);
        if (step2Label != null) step2Label.setVisibility(vis);
        if (step2Row != null) step2Row.setVisibility(vis);
        if (step3Label != null) step3Label.setVisibility(vis);
        if (step3Row != null) step3Row.setVisibility(vis);
        if (homeContent != null) {
            float density = getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) homeContent.getLayoutParams();
            if (tutorial) {
                p.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                p.topMargin = (int) (16 * density);
            } else {
                p.height = LinearLayout.LayoutParams.MATCH_PARENT;
                p.topMargin = 0;
            }
            homeContent.setLayoutParams(p);
        }
        if (pixelRippleView != null) {
            pixelRippleView.setTutorialMode(tutorial);
        }
        if (logoImage != null) {
            float density = getResources().getDisplayMetrics().density;
            int logoDp = tutorial ? 184 : 236;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    (int) (logoDp * density), (int) (logoDp * density));
            lp.gravity = Gravity.CENTER;
            logoImage.setLayoutParams(lp);
        }
        if (logoFrame != null) {
            float density = getResources().getDisplayMetrics().density;
            int frameDp = tutorial ? 204 : 256;
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                    (int) (frameDp * density), (int) (frameDp * density));
            fp.gravity = Gravity.CENTER;
            fp.topMargin = (int) ((tutorial ? 16 : 8) * density);
            if (!tutorial) {
                fp.bottomMargin = (int) (20 * density);
            }
            logoFrame.setLayoutParams(fp);
        }
        updateSwitchDetected();
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
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setSingleLine(false);
        valueView.setEllipsize(null);
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
            if (pixelRippleView != null) {
                pixelRippleView.setVisibility(View.VISIBLE);
            }

            if (tabHome != null) {
                styleTab(tabHome, true);
            }

            if (tabMore != null) {
                styleTab(tabMore, false);
            }
        } else {
            homeContent.setVisibility(View.GONE);
            moreContent.setVisibility(View.VISIBLE);
            if (pixelRippleView != null) {
                pixelRippleView.setVisibility(View.GONE);
            }

            if (tabHome != null) {
                styleTab(tabHome, false);
            }

            if (tabMore != null) {
                styleTab(tabMore, true);
            }
        }
    }

    private String readProxySwitchIp() {
        try {
            File f = new File(getFilesDir(), "proxy_events.jsonl");

            if (!f.exists()) {
                return null;
            }

            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 200000);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String text = new String(buf, "UTF-8");

            return V3Util.lastSwitchIpFromEvents(text);
        } catch (Exception e) {
            return null;
        }
    }

    private void applyLearnedSwitchIp() {
        String ip = readProxySwitchIp();

        if (ip == null) {
            ip = getPreferences(MODE_PRIVATE).getString("switch_ip_known", null);
        }

        if (ip == null || !ip.contains(".")) {
            return;
        }

        String phone = getPhoneWifiIp();

        if (phone != null && phone.contains(".") && !V3Util.sameSubnetPrefix(ip, phone)) {
            return;
        }

        getPreferences(MODE_PRIVATE).edit().putString("switch_ip_known", ip).apply();
        switchIpResult = ip;
        lastSwitchId = ip;
        updateSwitchBanner();
    }

    private void refreshMoreTab() {
        applyLearnedSwitchIp();

        setMoreValue("zt_installed", isZeroTierInstalled() ? "Yes" : "No");

        String ztIp = getZeroTierIp();

        setMoreValue("zt_ip", ztIp.equals("not found") ? "Not connected" : ztIp);

        setMoreValue("phone_ip", getPhoneWifiIp());

        setMoreValue("switch_ip", switchIpResult);

        setMoreValue("isolation", isolationText());

        setMoreValue("wifi_status", isWifiConnected() ? "Connected" : "Not connected");

        setMoreValue("zt_status", isVpnConnected() ? "Connected" : "Not connected");

        setMoreValue("grid0_network", isGrid0Connected() ? "Connected" : "Not connected");

        setMoreValue("network_id", GRID0_NETWORK_ID);

        startLanScan(true);
    }

    private String isolationText() {
        return isolationDetail.isEmpty() ? isolationResult : isolationResult + "\n" + isolationDetail;
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            if (cm == null) {
                return false;
            }

            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);

                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    private void setMoreValue(String key, String value) {
        TextView view = moreValues.get(key);

        if (view != null) {
            view.setText(value);
        }
    }

    private static final String[] NINTENDO_OUIS = {
        "00:09:BF", "00:17:AB", "00:19:1D", "00:1B:7A", "00:1C:BE", "00:22:4C",
        "00:24:44", "00:25:A0", "00:26:59", "2C:10:C1", "34:AF:2C", "40:F3:08",
        "58:2F:40", "60:6B:FF", "7C:BB:8A", "8C:CD:E8", "CC:9E:00"
    };

    private String tryResolveName(String ip) {
        try {
            String host = InetAddress.getByName(ip).getCanonicalHostName();
            if (host != null && !host.equals(ip)) {
                return host;
            }
        } catch (Exception e) {
        }
        return null;
    }

    private void updateSwitchDetected() {
        startLanScan(false);
        updateSwitchBanner();
    }

    private void updateSwitchBanner() {
        if (switchDetectedView == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (switchDetectedView == null) return;
                if (currentTab != 0 || lastSwitchId == null) {
                    switchDetectedView.setVisibility(View.GONE);
                    return;
                }
                switchDetectedView.setText("Switch detected: " + lastSwitchId);
                switchDetectedView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void startLanScan(boolean force) {
        long now = SystemClock.elapsedRealtime();

        synchronized (this) {
            if (lanScanRunning) return;
            if (!force && now - lastLanScanMs < 10000) return;
            lanScanRunning = true;
            lastLanScanMs = now;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                runLanScan();
            }
        }).start();
    }

    private void runLanScan() {
        try {
            int phoneIp = 0;
            int mask = 0xFFFFFF00;
            int gatewayIp = 0;

            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

                if (wifi != null) {
                    DhcpInfo dhcp = wifi.getDhcpInfo();

                    if (dhcp != null && dhcp.ipAddress != 0) {
                        phoneIp = Integer.reverseBytes(dhcp.ipAddress);
                        gatewayIp = Integer.reverseBytes(dhcp.gateway);

                        if (dhcp.netmask != 0) {
                            mask = Integer.reverseBytes(dhcp.netmask);
                        }
                    }

                    if (phoneIp == 0 && wifi.getConnectionInfo() != null) {
                        phoneIp = Integer.reverseBytes(wifi.getConnectionInfo().getIpAddress());
                    }
                }
            } catch (Exception e) {
            }

            if (phoneIp == 0) {
                phoneIp = ipStringToInt(getPhoneWifiIp());
            }

            if (phoneIp == 0) {
                isolationResult = "Wi-Fi is not connected";
                isolationDetail = "";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setMoreValue("isolation", isolationText());
                    }
                });
                return;
            }

            final int fPhoneIp = phoneIp;
            final int fGatewayIp = gatewayIp;
            int network = phoneIp & mask;
            int broadcast = network | ~mask;
            int total = broadcast - network - 1;
            final List<Integer> hosts = new ArrayList<>();

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

            final Set<Integer> alive = Collections.synchronizedSet(new HashSet<Integer>());
            final Set<Integer> discoverySenders = Collections.synchronizedSet(new HashSet<Integer>());

            DatagramSocket discSock = null;

            try {
                discSock = new DatagramSocket(null);
                discSock.setReuseAddress(true);
                discSock.bind(new java.net.InetSocketAddress(35000));
                discSock.setBroadcast(true);
                bindToWifi(discSock);
                discSock.setSoTimeout(500);
            } catch (Exception e) {
                discSock = null;
            }

            if (discSock != null) {
                final DatagramSocket fDisc = discSock;
                Thread discThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[2048];

                        while (!fDisc.isClosed()) {
                            try {
                                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                                fDisc.receive(pkt);
                                int src = ipStringToInt(pkt.getAddress().getHostAddress());

                                if (src != fPhoneIp && src != fGatewayIp) {
                                    discoverySenders.add(src);
                                }
                            } catch (Exception e) {
                                if (fDisc.isClosed()) break;
                            }
                        }
                    }
                });
                discThread.setDaemon(true);
                discThread.start();
            }

            DatagramSocket socket = null;

            try {
                socket = new DatagramSocket();
                bindToWifi(socket);
                final DatagramSocket sweepSocket = socket;
                final byte[] ping = new byte[]{0};
                ExecutorService pool = Executors.newFixedThreadPool(32);

                for (int round = 0; round < 2; round++) {
                    for (final int host : hosts) {
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    sweepSocket.send(new DatagramPacket(ping, 1, InetAddress.getByAddress(intToIpBytes(host)), 5353));
                                } catch (Exception e) {
                                }
                            }
                        });
                    }
                }

                pool.shutdown();
                pool.awaitTermination(6, TimeUnit.SECONDS);
                Thread.sleep(1200);
            } catch (Exception e) {
            } finally {
                if (socket != null) socket.close();
            }

            try {
                ExecutorService pool2 = Executors.newFixedThreadPool(32);

                for (final int host : hosts) {
                    pool2.execute(new Runnable() {
                        @Override
                        public void run() {
                            DatagramSocket probe = null;

                            try {
                                probe = new DatagramSocket();
                                bindToWifi(probe);
                                InetAddress addr = InetAddress.getByAddress(intToIpBytes(host));
                                probe.connect(addr, 5353);
                                probe.setSoTimeout(350);
                                byte[] out = new byte[]{0};
                                probe.send(new DatagramPacket(out, 1, addr, 5353));
                                byte[] buf = new byte[64];
                                probe.receive(new DatagramPacket(buf, buf.length));
                                alive.add(host);
                            } catch (java.net.PortUnreachableException e) {
                                alive.add(host);
                            } catch (Exception e) {
                            } finally {
                                if (probe != null) probe.close();
                            }
                        }
                    });
                }

                pool2.shutdown();
                pool2.awaitTermination(15, TimeUnit.SECONDS);
            } catch (Exception e) {
            }

            Map<Integer, String> arpAll = new HashMap<>();

            try {
                BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"));
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("IP address")) continue;
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 4) continue;
                    String mac = parts[3].toUpperCase();
                    if (mac.equals("00:00:00:00:00:00")) continue;
                    int ip = ipStringToInt(parts[0]);
                    if (ip < network || ip > broadcast) continue;
                    arpAll.put(ip, mac);
                }

                reader.close();
            } catch (Exception e) {
            }

            String gatewayMac = arpAll.get(fGatewayIp);
            Map<Integer, String> arp = new HashMap<>();

            for (Map.Entry<Integer, String> entry : arpAll.entrySet()) {
                if (entry.getKey() == fPhoneIp || entry.getKey() == fGatewayIp) continue;
                if (gatewayMac != null && gatewayMac.equals(entry.getValue())) continue;
                arp.put(entry.getKey(), entry.getValue());
            }

            if (discSock != null) {
                try {
                    discSock.close();
                } catch (Exception e) {
                }
            }

            alive.addAll(arp.keySet());
            alive.addAll(discoverySenders);

            String foundSwitchIp = null;
            String foundSwitchId = null;

            for (Map.Entry<Integer, String> entry : arp.entrySet()) {
                for (String oui : NINTENDO_OUIS) {
                    if (entry.getValue().startsWith(oui)) {
                        foundSwitchIp = intToIpString(entry.getKey());
                        String name = tryResolveName(foundSwitchIp);
                        foundSwitchId = name != null ? name : entry.getValue();
                        break;
                    }
                }

                if (foundSwitchIp != null) break;
            }

            if (foundSwitchIp == null && !discoverySenders.isEmpty()) {
                Integer sender = discoverySenders.iterator().next();
                foundSwitchIp = intToIpString(sender);
                String name = tryResolveName(foundSwitchIp);
                foundSwitchId = name != null ? name : foundSwitchIp;
            }

            if (foundSwitchIp == null) {
                int checked = 0;

                for (Integer ip : alive) {
                    if (checked >= 16) break;
                    checked++;
                    String name = tryResolveName(intToIpString(ip));

                    if (name != null) {
                        String lower = name.toLowerCase();

                        if (lower.contains("nintendo") || lower.contains("switch")) {
                            foundSwitchIp = intToIpString(ip);
                            foundSwitchId = name;
                            break;
                        }
                    }
                }
            }

            if (foundSwitchIp != null) {
                switchIpResult = foundSwitchIp;
                lastSwitchId = foundSwitchId;
                switchSubnet = network;
            } else if (switchSubnet != network) {
                switchIpResult = "Not detected";
                lastSwitchId = null;
                switchSubnet = network;
                applyLearnedSwitchIp();
            }

            int dns1 = 0;
            int dns2 = 0;

            try {
                WifiManager wifi2 = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

                if (wifi2 != null) {
                    DhcpInfo dhcp2 = wifi2.getDhcpInfo();

                    if (dhcp2 != null) {
                        dns1 = Integer.reverseBytes(dhcp2.dns1);
                        dns2 = Integer.reverseBytes(dhcp2.dns2);
                    }
                }
            } catch (Exception e) {
            }

            final int fDns1 = dns1;
            final int fDns2 = dns2;
            final Set<Integer> pingAlive = Collections.synchronizedSet(new HashSet<Integer>());
            final String phoneIpStr = intToIpString(fPhoneIp);

            boolean useBind = false;
            boolean usePingBin = true;

            if (fGatewayIp != 0) {
                Boolean canary = pingOnce(new String[]{"/system/bin/ping", "-c", "1", "-W", "1", "-I", phoneIpStr, intToIpString(fGatewayIp)});

                if (canary != null && canary) {
                    useBind = true;
                } else {
                    Boolean canary2 = pingOnce(new String[]{"/system/bin/ping", "-c", "1", "-W", "1", intToIpString(fGatewayIp)});

                    if (canary2 == null || !canary2) {
                        usePingBin = false;
                    }
                }
            }

            final boolean fUseBind = useBind;
            final boolean fUsePingBin = usePingBin;
            final java.util.concurrent.atomic.AtomicBoolean pingBroken = new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                ExecutorService pool3 = Executors.newFixedThreadPool(32);

                for (final int host : hosts) {
                    if (host == fGatewayIp || host == fDns1 || host == fDns2) continue;

                    pool3.execute(new Runnable() {
                        @Override
                        public void run() {
                            String target = intToIpString(host);

                            if (fUsePingBin && !pingBroken.get()) {
                                String[] cmd = fUseBind
                                        ? new String[]{"/system/bin/ping", "-c", "1", "-W", "1", "-I", phoneIpStr, target}
                                        : new String[]{"/system/bin/ping", "-c", "1", "-W", "1", target};
                                Boolean ok = pingOnce(cmd);

                                if (ok != null) {
                                    if (ok) {
                                        pingAlive.add(host);
                                    }

                                    return;
                                }

                                pingBroken.set(true);
                            }
                        }
                    });
                }

                pool3.shutdown();
                pool3.awaitTermination(30, TimeUnit.SECONDS);
            } catch (Exception e) {
            }

            alive.addAll(pingAlive);

            isolationDetail = "";

            if (!fUsePingBin || pingBroken.get()) {
                isolationResult = "Could not check";
            } else if (pingAlive.isEmpty()) {
                isolationResult = "Most likely On (bad)";
            } else {
                isolationResult = "Most likely Off (good)";
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setMoreValue("switch_ip", switchIpResult);
                    setMoreValue("isolation", isolationText());
                    updateSwitchBanner();
                }
            });
        } catch (Exception e) {
        } finally {
            synchronized (this) {
                lanScanRunning = false;
            }
        }
    }

    private Boolean pingOnce(String[] cmd) {
        Process proc = null;

        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            long deadline = SystemClock.elapsedRealtime() + 1600;

            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    return proc.exitValue() == 0;
                } catch (IllegalThreadStateException e) {
                    try {
                        Thread.sleep(60);
                    } catch (Exception e2) {
                    }
                }
            }

            return false;
        } catch (Exception e) {
            return null;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Exception e) {
                }
            }
        }
    }

    private void bindToWifi(DatagramSocket socket) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            if (cm == null) return;

            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);

                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    network.bindSocket(socket);
                    return;
                }
            }
        } catch (Exception e) {
        }
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

    private String intToIpString(int ip) {
        return ((ip >> 24) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 8) & 0xff) + "." + (ip & 0xff);
    }

    private int prefixForIp(String ip, int fallback) {
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();

            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();

                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();

                    if (addr != null && ip.equals(addr.getHostAddress())) {
                        int p = ia.getNetworkPrefixLength();

                        if (p > 0 && p < 32) {
                            return p;
                        }

                        return fallback;
                    }
                }
            }
        } catch (Exception e) {
        }

        return fallback;
    }

    private int switchPrefixFor(String phoneIp) {
        int p = prefixForIp(phoneIp, -1);

        if (p > 0) {
            return p;
        }

        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            if (wifi != null) {
                DhcpInfo dhcp = wifi.getDhcpInfo();

                if (dhcp != null && dhcp.netmask != 0) {
                    int bits = V3Util.prefixFromDhcpNetmask(dhcp.netmask);

                    if (bits > 0) {
                        return bits;
                    }
                }
            }
        } catch (Exception e) {
        }

        return 24;
    }

    private void toggleProxy() {
        if (!proxyRunning) {
            buzz();
            if (!isGrid0Connected()) {
                Toast.makeText(this, "Connect to the GRID0 network first", Toast.LENGTH_LONG).show();

                return;
            }

            String phoneIp = getPhoneWifiIp();

            String ztIp = getZeroTierIp();

            File keyFile = prepareGameKeyFile();

            if (keyFile == null) {
                Toast.makeText(this, "Game key missing", Toast.LENGTH_LONG).show();

                return;
            }

            boolean detailed = getPreferences(MODE_PRIVATE).getBoolean("detailed_log", false);

            Intent service = new Intent(this, ProxyService.class);
            service.putExtra("phoneIp", phoneIp);
            service.putExtra("ztIp", ztIp);
            service.putExtra("switchPrefix", switchPrefixFor(phoneIp));
            service.putExtra("hostPrefix", prefixForIp(ztIp, 24));
            service.putExtra("detailed", detailed);

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }

            logText = "PHONE_IP = " + phoneIp + "\nZT_IP = " + ztIp + "\nProxy started\n";
            proxyRunning = true;

            if (logView != null) {
                logView.setText(logText);
            }

            updateProxyUI();
            startLogRefresh();
        } else {
            try {
                if (Python.isStarted()) {
                    Python.getInstance().getModule("zttestproxyv228").callAttr("request_stop");
                }
            } catch (Exception e) {
            }

            stopService(new Intent(this, ProxyService.class));
            proxyRunning = false;
            stopLogRefresh();

            if (logView != null) {
                refreshProxyLog();
            }

            updateProxyUI();
        }
    }

    private final Handler logRefreshHandler = new Handler(Looper.getMainLooper());

    private final Runnable logRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!proxyRunning) {
                return;
            }

            if (!ProxyService.running) {
                proxyRunning = false;

                if (!ProxyService.lastError.isEmpty() && logView != null) {
                    logView.setText(logView.getText() + "\nProxy stopped: " + ProxyService.lastError + "\n");
                }

                updateProxyUI();

                return;
            }

            refreshProxyLog();
            applyLearnedSwitchIp();
            setMoreValue("switch_ip", switchIpResult);
            logRefreshHandler.postDelayed(this, 2000);
        }
    };

    private void startLogRefresh() {
        logRefreshHandler.removeCallbacks(logRefreshRunnable);
        logRefreshHandler.postDelayed(logRefreshRunnable, 1000);
    }

    private void stopLogRefresh() {
        logRefreshHandler.removeCallbacks(logRefreshRunnable);
    }

    private void refreshProxyLog() {
        if (logView == null) {
            return;
        }

        try {
            File f = new File(getFilesDir(), "proxy_log.txt");

            if (!f.exists()) {
                return;
            }

            BufferedReader reader = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            reader.close();
            String text = sb.toString();

            if (text.length() > 24000) {
                text = text.substring(text.length() - 24000);
            }

            if (!text.contentEquals(logView.getText())) {
                logView.setText(text);

                if (logScroll != null) {
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        } catch (Exception e) {
        }
    }

    private void checkForUpdates() {
        if (updateChecked) {
            return;
        }

        updateChecked = true;

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new java.net.URL(
                        "https://raw.githubusercontent.com/GRID0-net/GRID0-ofw/main/android/version.json").openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }

                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String remote = json.getString("version");
                String url = json.optString("url", "");
                String local = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;

                if (local != null && isNewerVersion(remote, local)) {
                    runOnUiThread(() -> {
                        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                        builder.setTitle("Update available");
                        builder.setMessage("GRID0 ofw " + remote + " is available. You have " + local + ".");
                        builder.setNegativeButton("Later", null);

                        if (!url.isEmpty()) {
                            builder.setPositiveButton("Update", (dialog, which) -> {
                                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(web);
                            });
                        }

                        builder.show();
                    });
                }
            } catch (Exception e) {
            }
        }).start();
    }

    private boolean isNewerVersion(String remote, String local) {
        return V3Util.isNewerVersion(remote, local);
    }

    private File prepareGameKeyFile() {
        File out = new File(getFilesDir(), "game_key.txt");

        if (out.exists() && out.length() > 0) {
            return out;
        }

        try (InputStream in = getAssets().open("game_key.txt");
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[4096];
            int n;

            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }

            return out;
        } catch (Exception e) {
            return null;
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
        pixelRippleView.startRipples();
        waveAnimator = ValueAnimator.ofFloat(0f, 1f);
        waveAnimator.setDuration(2000);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new LinearInterpolator());
        waveAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pixelRippleView.setWavePhase((Float) a.getAnimatedValue());
            }
        });
        waveAnimator.start();
        spinAnimator = ValueAnimator.ofFloat(0f, (float)(2 * Math.PI));
        spinAnimator.setDuration(45000);
        spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        spinAnimator.setInterpolator(new LinearInterpolator());
        spinAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pixelRippleView.setSpinAngle((Float) a.getAnimatedValue());
            }
        });
        spinAnimator.start();
    }

    private void stopRipple() {
        if (waveAnimator != null) {
            waveAnimator.cancel();
            waveAnimator = null;
        }
        if (spinAnimator != null) {
            spinAnimator.cancel();
            spinAnimator = null;
        }
        if (pixelRippleView != null) {
            pixelRippleView.stopRipples();
        }
    }

    private static class PixelRippleView extends View {
        private static final float[] RIPPLE_RADII_DP = {97f, 107f, 117f, 126f, 133f, 136f};
        private static final int[] RIPPLE_COLORS = {0xFFFF0000, 0xFFE60000, 0xFFCC0000, 0xFFB30000, 0xFF990000, 0xFF800000};
        private static final float LOGO_EDGE_DP = 80f;
        private static final float RIPPLE_STAGGER_MS = 150f;
        private static final float EXPAND_MS = 600f;
        private static final float FIZZLE_START_MS = 8000f;
        private static final float FIZZLE_MS = 12000f;
        private final Paint fillPaint = new Paint();
        private final Paint strokePaint = new Paint();
        private long rippleStartMs = -1L;
        private float wavePhase = 0f;
        private float spinAngle = 0f;
        private float centerY = -1f;
        private float borderProgress = 0f;
        private boolean tutorialMode = true;

        public void setTutorialMode(boolean t) {
            tutorialMode = t;
            invalidate();
        }

        public PixelRippleView(Context context) {
            super(context);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setAntiAlias(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setAntiAlias(false);
        }

        public void startRipples() {
            rippleStartMs = SystemClock.uptimeMillis();
            invalidate();
        }

        public void stopRipples() {
            rippleStartMs = -1L;
            wavePhase = 0f;
            spinAngle = 0f;
            invalidate();
        }

        public void setWavePhase(float phase) {
            wavePhase = phase;
            invalidate();
        }

        public void setSpinAngle(float angle) {
            spinAngle = angle;
            invalidate();
        }

        public void setCenterY(float y) {
            centerY = y;
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
            float cy = centerY >= 0 ? centerY : getHeight() / 2f;
            int ringColor = lerpColor(0xFF808080, 0xFFFF0000, borderProgress);
            strokePaint.setStrokeWidth(3f * density);
            strokePaint.setColor(ringColor);
            strokePaint.setAntiAlias(true);
            float edgeR = (tutorialMode ? 92f : 118f) * density;
            canvas.drawCircle(cx, cy, edgeR, strokePaint);
            if (rippleStartMs < 0L) return;
            long rippleT = SystemClock.uptimeMillis() - rippleStartMs;
            float logoEdge = tutorialMode ? 92f : 118f;
            int count = tutorialMode ? 6 : 24;
            float stagger = tutorialMode ? RIPPLE_STAGGER_MS : 30f;
            float fizzle = 0f;
            if (tutorialMode) {
                fizzle = Math.min(Math.max((rippleT - FIZZLE_START_MS) / FIZZLE_MS, 0f), 1f);
            }
            for (int i = 0; i < count; i++) {
                long born = rippleT - (long) (i * stagger);
                if (born < 0L) continue;
                float grow = Math.min(born / EXPAND_MS, 1f);
                float ease = 1f - (1f - grow) * (1f - grow);
                float targetR = tutorialMode ? RIPPLE_RADII_DP[i] : (logoEdge + 5f + 5f * i + 0.12f * i * i);
                float r = (logoEdge + (targetR - logoEdge) * ease) * density;
                float alpha = 0.9f * Math.min(born / 300f, 1f);
                int color;
                if (tutorialMode) {
                    if (i >= 3) {
                        alpha *= (1f - fizzle);
                        color = RIPPLE_COLORS[i];
                    } else if (i == 1) {
                        color = lerpColor(RIPPLE_COLORS[1], RIPPLE_COLORS[3], fizzle);
                    } else if (i == 2) {
                        color = lerpColor(RIPPLE_COLORS[2], RIPPLE_COLORS[5], fizzle);
                    } else {
                        color = RIPPLE_COLORS[0];
                    }
                } else {
                    color = lerpColor(0xFFFF0000, 0xFF800000, i / 23f);
                }
                if (alpha <= 0f) continue;
                fillPaint.setColor(color);
                fillPaint.setAlpha((int) (alpha * 255));
                float sqDp = tutorialMode ? (4.5f - i * 0.5f) : (4.5f - i * (3f / 23f));
                drawDotRing(canvas, cx, cy, r, targetR, spinAngle, fillPaint, sqDp);
            }
        }

        private void drawDotRing(Canvas canvas, float cx, float cy, float r, float targetR, float spinAngle, Paint paint, float sqDp) {
            float density = getResources().getDisplayMetrics().density;
            float sq = sqDp * density;
            paint.setAntiAlias(true);
            int n = Math.max(48, (int) Math.round((2 * Math.PI * targetR) / 6f));
            for (int j = 0; j < n; j++) {
                double a = (j / (double) n) * 2 * Math.PI - Math.PI / 2 + spinAngle;
                float wobble = 1f + 0.03f * (float) Math.sin(2 * Math.PI * (j / (float) n * 4f + wavePhase));
                float rr = r * wobble;
                float x = cx + rr * (float) Math.cos(a);
                float y = cy + rr * (float) Math.sin(a);
                canvas.drawCircle(x, y, sq / 2f, paint);
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
        updateSteps();

        if (consoleBox != null) {
            consoleBox.setVisibility(proxyRunning ? View.VISIBLE : View.GONE);
        }

        if (socialRow != null && discordBtn != null && supportBtn != null) {
            float density = getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams socialLp = (LinearLayout.LayoutParams) socialRow.getLayoutParams();
            LinearLayout.LayoutParams discordLp = (LinearLayout.LayoutParams) discordBtn.getLayoutParams();
            LinearLayout.LayoutParams supportLp = (LinearLayout.LayoutParams) supportBtn.getLayoutParams();

            if (proxyRunning) {
                socialRow.setOrientation(LinearLayout.HORIZONTAL);
                socialLp.topMargin = 36;
                discordLp.width = (int) (136 * density);
                discordLp.rightMargin = (int) (4 * density);
                discordLp.bottomMargin = 0;
                supportLp.width = (int) (136 * density);
                supportLp.leftMargin = (int) (4 * density);
                supportLp.topMargin = 0;
            } else {
                socialRow.setOrientation(LinearLayout.VERTICAL);
                socialLp.topMargin = 108;
                discordLp.width = (int) (272 * density);
                discordLp.rightMargin = 0;
                discordLp.bottomMargin = 0;
                supportLp.width = (int) (272 * density);
                supportLp.leftMargin = 0;
                supportLp.topMargin = (int) (16 * density);
            }

            socialRow.setLayoutParams(socialLp);
            discordBtn.setLayoutParams(discordLp);
            supportBtn.setLayoutParams(supportLp);
            discordBtn.setText(proxyRunning ? "Discord" : "Join our Discord");

            if (toggleRow != null) {
                LinearLayout.LayoutParams toggleLp = (LinearLayout.LayoutParams) toggleRow.getLayoutParams();
                toggleLp.topMargin = (int) ((proxyRunning ? 10 : 30) * density);
                toggleRow.setLayoutParams(toggleLp);
            }
        }

        if (statusText != null) {
            statusText.setText(proxyRunning ? "ONLINE" : "OFFLINE");
            statusText.setTextColor(proxyRunning ? Color.RED : Color.GRAY);
            statusText.setTypeface(Typeface.create("sans-serif-medium", proxyRunning ? Typeface.BOLD : Typeface.NORMAL));
        }

        if (logoImage != null) {
            if (saturationAnimator != null) saturationAnimator.cancel();
            if (proxyRunning) {
                saturationAnimator = ValueAnimator.ofFloat(0.3f, 1f);
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
                matrix.setSaturation(0.3f);
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
