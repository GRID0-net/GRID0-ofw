package com.grid0.ofw;

import android.animation.ValueAnimator;
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
    private ValueAnimator waveAnimator;
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


    @Override
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
        zeroTierInstalled = isZeroTierInstalled();

        buildLayout();
        switchCheckHandler.postDelayed(switchCheckRunnable, 15000);
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
        boxParams.topMargin = (int) (15 * getResources().getDisplayMetrics().density);
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
        // tiny refresh button beside the isolation value, inside the box
        TextView isolationValue = moreValues.get("isolation");
        if (isolationValue != null && isolationValue.getParent() instanceof TableRow) {
            TableRow isoRow = (TableRow) isolationValue.getParent();
            ImageButton isoRefresh = new ImageButton(this);
            isoRefresh.setImageResource(android.R.drawable.ic_popup_sync);
            isoRefresh.setBackgroundColor(Color.TRANSPARENT);
            float density = getResources().getDisplayMetrics().density;
            TableRow.LayoutParams bp = new TableRow.LayoutParams(
                    (int) (24 * density), (int) (24 * density));
            bp.gravity = Gravity.CENTER_VERTICAL;
            isoRefresh.setLayoutParams(bp);
            isoRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkClientIsolation();
                }
            });
            isoRow.addView(isoRefresh, 1);
        }

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
        discordBg.setCornerRadius((int) (10 * getResources().getDisplayMetrics().density));
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

        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.gravity = Gravity.CENTER_HORIZONTAL;
        rowParams.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
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
        moreContent.addView(toggleRow);

        GradientDrawable logBorder = new GradientDrawable();
        logBorder.setColor(Color.BLACK);

        int borderWidth = (int) (2 * getResources().getDisplayMetrics().density);
        logBorder.setStroke(borderWidth, Color.WHITE);
        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (140 * getResources().getDisplayMetrics().density));

        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        logParams.leftMargin = margin;
        logParams.rightMargin = margin;
        logParams.topMargin = margin;
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
        moreContent.addView(logScroll);

        setContentView(root);

        if (proxyRunning && !logText.isEmpty()) {
            logView.setText(logText);
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (logoDp * density), (int) (logoDp * density));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            logoImage.setLayoutParams(lp);
        }
        if (logoFrame != null) {
            float density = getResources().getDisplayMetrics().density;
            int frameDp = tutorial ? 204 : 256;
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                    (int) (frameDp * density), (int) (frameDp * density));
            fp.gravity = Gravity.CENTER;
            fp.topMargin = (int) (16 * density);
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

    private static final String[] NINTENDO_OUIS = {
        "60:6B:FF", "7C:BB:8A", "CC:9E:00", "8C:CD:E8", "40:F3:08", "34:AF:2C"
    };

    private String[] detectSwitch() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("IP address")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                String mac = parts[3].toUpperCase();
                if (mac.equals("00:00:00:00:00:00")) continue;
                for (String oui : NINTENDO_OUIS) {
                    if (mac.startsWith(oui)) {
                        String ip = parts[0];
                        String name = tryResolveName(ip);
                        reader.close();
                        return new String[]{ip, mac, name};
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
        }
        return null;
    }

    private String tryResolveName(String ip) {
        try {
            String host = InetAddress.getByName(ip).getCanonicalHostName();
            if (host != null && !host.equals(ip) && !host.equalsIgnoreCase("switch")) {
                return host;
            }
        } catch (Exception e) {
        }
        return null;
    }

    private void updateSwitchDetected() {
        if (switchDetectedView == null) return;
        boolean tutorial = tutorialSwitch == null || tutorialSwitch.isChecked();
        if (tutorial || currentTab != 0) {
            final boolean t = tutorial;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    switchDetectedView.setVisibility(View.GONE);
                }
            });
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] sw = detectSwitch();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (sw == null) {
                            switchDetectedView.setVisibility(View.GONE);
                            return;
                        }
                        String id = sw[2] != null ? sw[2] : sw[1];
                        switchDetectedView.setText("Switch detected: " + id);
                        switchDetectedView.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    private void checkClientIsolation() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = scanForOtherDevices();
                isolationResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setMoreValue("isolation", result);
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
        if (!proxyRunning) {
            buzz();
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
        pixelRippleView.startRipples();
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
        if (waveAnimator != null) {
            waveAnimator.cancel();
            waveAnimator = null;
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
            float spinAngle = (SystemClock.uptimeMillis() - rippleStartMs) * (float)(2 * Math.PI) / 45000f;
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
            int n = Math.max(48, (int) Math.round((2 * Math.PI * targetR * density) / 6f));
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

        if (logScroll != null) {
            logScroll.setVisibility(proxyRunning ? View.VISIBLE : View.GONE);
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
