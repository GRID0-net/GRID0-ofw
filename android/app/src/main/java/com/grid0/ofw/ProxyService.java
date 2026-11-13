package com.grid0.ofw;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;

public class ProxyService extends Service {
    public static volatile boolean running = false;
    public static volatile String lastError = "";

    private Thread proxyThread = null;
    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    "grid0_proxy", "GRID0 proxy", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String phoneIp = intent != null ? intent.getStringExtra("phoneIp") : null;
        String ztIp = intent != null ? intent.getStringExtra("ztIp") : null;
        boolean detailed = intent != null && intent.getBooleanExtra("detailed", false);

        if (phoneIp == null || ztIp == null) {
            stopSelf();

            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification());
        acquireLocks();

        File files = getFilesDir();
        setProxyEnv("PHONE_IP", phoneIp);
        setProxyEnv("ZT_IP", ztIp);
        setProxyEnv("GAME_KEY_FILE", new File(files, "game_key.txt").getAbsolutePath());
        setProxyEnv("CAPTURE", new File(files, "proxy_capture").getAbsolutePath());
        setProxyEnv("EVENTS", new File(files, "proxy_events.jsonl").getAbsolutePath());
        setProxyEnv("PROXY_LOG", new File(files, "proxy_log.txt").getAbsolutePath());
        setProxyEnv("DETAILED_LOG", detailed ? "1" : "0");

        new File(files, "proxy_log.txt").delete();

        lastError = "";
        proxyThread = new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(ProxyService.this));
                }

                Python.getInstance().getModule("zttestproxyv228").callAttr("main");
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "proxy error";
            }

            running = false;
            stopSelf();
        });
        proxyThread.setDaemon(true);
        running = true;
        proxyThread.start();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("zttestproxyv228").callAttr("request_stop");
            }
        } catch (Exception e) {
        }

        running = false;
        releaseLocks();
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "grid0_proxy")
                : new Notification.Builder(this);

        return builder
                .setContentTitle("GRID0 proxy running")
                .setContentText("LAN play relay is on")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grid0:proxy");
                wakeLock.acquire();
            }
        } catch (Exception e) {
        }

        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "grid0:proxy");
                wifiLock.acquire();
            }
        } catch (Exception e) {
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
        }

        wifiLock = null;
        wakeLock = null;
    }

    private void setProxyEnv(String name, String value) {
        try {
            Os.setenv(name, value, true);
        } catch (Exception e) {
        }

        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("os").get("environ").callAttr("__setitem__", name, value);
            }
        } catch (Exception e) {
        }
    }
}
