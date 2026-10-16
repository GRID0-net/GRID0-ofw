package com.grid0.ofw;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.logo);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setLayoutParams(new ViewGroup.LayoutParams(600, 600));
        button.setOnClickListener(v -> {});

        layout.addView(button);
        setContentView(layout);
    }
}
