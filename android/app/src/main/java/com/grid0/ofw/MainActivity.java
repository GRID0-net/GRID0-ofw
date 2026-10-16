package com.grid0.ofw;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String VERSION_URL = "https://raw.githubusercontent.com/GRID0-net/GRID0-ofw/3.0.0/android/version.json";

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
        button.setOnClickListener(v -> checkForUpdate());

        layout.addView(button);
        setContentView(layout);
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                JSONObject info = fetchJson(VERSION_URL);
                String latest = info.getString("version");
                String url = info.getString("url");
                String current = currentVersion();
                if (latest.equals(current)) {
                    toast("Already on latest version");
                    return;
                }
                toast("Downloading update " + latest);
                File apk = downloadApk(url);
                runOnUiThread(() -> installApk(apk));
            } catch (Exception e) {
                toast("Update check failed");
            }
        }).start();
    }

    private JSONObject fetchJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    private String currentVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private File downloadApk(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        File apk = new File(getExternalFilesDir(null), "update.apk");
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return apk;
    }

    private void installApk(File apk) {
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try (OutputStream out = session.openWrite("update", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction("INSTALL_COMPLETE");
            PendingIntent pending = PendingIntent.getActivity(this, sessionId, intent, PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            session.close();
        } catch (Exception e) {
            toast("Install failed");
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }
}
