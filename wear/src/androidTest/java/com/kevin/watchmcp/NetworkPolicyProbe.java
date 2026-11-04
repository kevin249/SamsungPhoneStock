package com.kevin.watchmcp;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** 真实断开 Wi-Fi；结果保存在设备本地，不依赖 ADB 持续在线。 */
final class NetworkPolicyProbe {
    private final Instrumentation test;
    private final Context context;
    private final JSONArray records = new JSONArray();
    private final long started = System.currentTimeMillis();
    private String phase = "starting";
    private boolean holdWifiOff;

    NetworkPolicyProbe(Instrumentation test) { this.test = test; context = test.getTargetContext(); }

    @android.annotation.SuppressLint("WearRecents")
    void run() throws Exception {
        boolean debuggingWasEnabled = android.provider.Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        try {
            test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitTransport(NetworkCapabilities.TRANSPORT_WIFI, 40000);
            shell("input keyevent KEYCODE_HOME");
            shell("input keyevent KEYCODE_SLEEP");
            phase = "wifi_screen_off";
            observe(35000, NetworkCapabilities.TRANSPORT_WIFI);
            holdWifiOff = true;
            phase = "wifi_disabling";
            record();
            shell("svc wifi disable");
            awaitTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH, 30000);
            phase = "bluetooth_screen_off";
            shell("input keyevent KEYCODE_SLEEP");
            observe(45000, NetworkCapabilities.TRANSPORT_BLUETOOTH);
            phase = "wifi_enabling";
            record();
            holdWifiOff = false;
            shell("svc wifi enable");
            awaitTransport(NetworkCapabilities.TRANSPORT_WIFI, 45000);
            phase = "wifi_restored_screen_off";
            shell("input keyevent KEYCODE_SLEEP");
            observe(45000, NetworkCapabilities.TRANSPORT_WIFI);
            phase = "PASS";
            record();
        } catch (Throwable failure) {
            phase = "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            record();
            throw failure;
        } finally {
            if (holdWifiOff) shell("svc wifi enable");
            if (debuggingWasEnabled) shell("settings put global adb_wifi_enabled 1");
        }
    }

    private void awaitTransport(int transport, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (holdWifiOff) shell("svc wifi disable");
            record();
            if (isTransport(transport)) return;
            SystemClock.sleep(500);
        }
        throw new AssertionError("Transport unavailable: " + transport);
    }

    private boolean isTransport(int transport) {
        WifiNetworkPolicy policy = WifiNetworkPolicy.current();
        Network network = policy == null ? null : policy.selectedNetwork();
        NetworkCapabilities caps = network == null ? null : context.getSystemService(ConnectivityManager.class).getNetworkCapabilities(network);
        return policy != null && policy.isWifiRequested() && caps != null && caps.hasTransport(transport)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void observe(long duration, int expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (holdWifiOff) shell("svc wifi disable");
            if (!isTransport(expected)) throw new AssertionError("Unexpected network during " + phase);
            record();
            SystemClock.sleep(1000);
        }
        record();
    }

    private void record() throws Exception {
        WifiNetworkPolicy policy = WifiNetworkPolicy.current();
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        Network network = policy == null ? null : policy.selectedNetwork();
        JSONObject row = new JSONObject().put("phase", phase).put("watch_time_ms", System.currentTimeMillis())
                .put("elapsed_ms", SystemClock.elapsedRealtime()).put("route", policy == null ? "none" : policy.transport())
                .put("network", String.valueOf(network)).put("bound_network", String.valueOf(connectivity.getBoundNetworkForProcess()))
                .put("wifi_requested", policy != null && policy.isWifiRequested()).put("interactive", power.isInteractive())
                .put("device_idle", power.isDeviceIdleMode())
                .put("status", new McpHistory(context.getSharedPreferences("watch", Context.MODE_PRIVATE)).snapshot().optString("status"));
        JSONArray posted = new JSONArray();
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getPostTime() >= started && notification.getId() == McpService.MESSAGE_ID) {
                posted.put(new JSONObject().put("event_id", notification.getTag()).put("posted_watch_ms", notification.getPostTime()));
            }
        }
        row.put("notifications", posted);
        records.put(row);
        Files.write(new File(context.getFilesDir(), "network-policy-test.json").toPath(), records.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = test.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            while (input.read() != -1) { }
        }
    }
}
