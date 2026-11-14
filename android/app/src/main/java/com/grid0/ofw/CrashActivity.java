package com.grid0.ofw;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.view.Gravity;

public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String crash = getIntent().getStringExtra("crash");
        if (crash == null) crash = "Unknown crash";
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        TextView tv = new TextView(this);
        tv.setText(crash);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setPadding(16, 16, 16, 16);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        setContentView(sv);
    }
}
