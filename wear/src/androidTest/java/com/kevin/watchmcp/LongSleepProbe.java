package com.kevin.watchmcp;

import android.app.AlarmManager;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import org.json.JSONArray;
import org.json.JSONObject;

/** 只在阶段边界采集；关闭无线调试、不持唤醒锁、不循环轮询，保留正式服务自然运行。 */
final class LongSleepProbe {
    private final Instrumentation test;
    private final Context context;
    private final JSONArray records = new JSONArray();
    private final long started = System.currentTimeMillis();
    private final String action = "com.kevin.watchmcp.TEST_SLEEP_PHASE";
    private volatile CountDownLatch alarmFired;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            CountDownLatch latch = alarmFired;
            if (latch != null) latch.countDown();
        }
    };

    LongSleepProbe(Instrumentation test) { this.test = test; context = test.getTargetContext(); }

    @android.annotation.SuppressLint("WearRecents")
    void run() throws Exception {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        if (!power.isIgnoringBatteryOptimizations(context.getPackageName())) throw new AssertionError("Background exemption missing");
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) throw new AssertionError("Phase timer unavailable");
        boolean debugging = android.provider.Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        PendingIntent alarm = PendingIntent.getBroadcast(context, 751,
                new Intent(action).setPackage(context.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean forced = false;
        try {
            test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = SystemClock.elapsedRealtime() + 40000;
            while (SystemClock.elapsedRealtime() < deadline) {
                WifiNetworkPolicy policy = WifiNetworkPolicy.current();
                String status = new McpHistory(context.getSharedPreferences("watch", Context.MODE_PRIVATE)).snapshot().optString("status");
                if (policy != null && "Wi-Fi".equals(policy.transport()) && status.contains("已连接")) break;
                SystemClock.sleep(500);
            }
            if (WifiNetworkPolicy.current() == null || !"Wi-Fi".equals(WifiNetworkPolicy.current().transport()))
                throw new AssertionError("Wi-Fi not selected at start");
            if (!new McpHistory(context.getSharedPreferences("watch", Context.MODE_PRIVATE)).snapshot().optString("status").contains("已连接"))
                throw new AssertionError("MCP not connected at start");
            shell("input keyevent KEYCODE_HOME");
            shell("input keyevent KEYCODE_SLEEP");
            record("natural_start");
            // 消息在第15分钟发送；16分钟边界闹钟仅用于取样和进入下一阶段。
            schedule(alarms, alarm, 16 * 60000L);
            // 启动端读到natural_start后关闭无线调试；此处不轮询，也不持有唤醒锁。
            alarmFired.await();
            record("natural_end");
            shell("input keyevent KEYCODE_SLEEP");
            forced = true;
            shell("dumpsys deviceidle force-idle");
            record("deep_start");
            if (!power.isDeviceIdleMode()) throw new AssertionError("Device failed to enter forced Doze");
            // 间隔大于Doze下的闹钟最小配额窗口；消息在第8和第10分钟发送。
            schedule(alarms, alarm, 11 * 60000L);
            alarmFired.await();
            record("deep_end");
            record("COMPLETE");
        } catch (Throwable error) {
            record("FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            throw error;
        } finally {
            alarms.cancel(alarm);
            context.unregisterReceiver(receiver);
            if (forced) shell("dumpsys deviceidle unforce");
            if (debugging) shell("settings put global adb_wifi_enabled 1");
        }
    }

    private void schedule(AlarmManager alarms, PendingIntent alarm, long delay) {
        alarmFired = new CountDownLatch(1);
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarm);
    }

    private void record(String phase) throws Exception {
        WifiNetworkPolicy policy = WifiNetworkPolicy.current();
        Network selected = policy == null ? null : policy.selectedNetwork();
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        JSONObject row = new JSONObject().put("phase", phase).put("watch_time_ms", System.currentTimeMillis())
                .put("version_name", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName)
                .put("version_code", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode())
                .put("elapsed_ms", SystemClock.elapsedRealtime()).put("uptime_ms", SystemClock.uptimeMillis())
                .put("interactive", power.isInteractive()).put("device_idle", power.isDeviceIdleMode())
                .put("battery_exempt", power.isIgnoringBatteryOptimizations(context.getPackageName()))
                .put("wireless_debugging", android.provider.Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1)
                .put("route", policy == null ? "none" : policy.transport()).put("network", String.valueOf(selected))
                .put("bound_network", String.valueOf(connectivity.getBoundNetworkForProcess()))
                .put("wifi_requested", policy != null && policy.isWifiRequested())
                .put("capabilities", String.valueOf(selected == null ? null : connectivity.getNetworkCapabilities(selected)))
                .put("status", new McpHistory(context.getSharedPreferences("watch", Context.MODE_PRIVATE)).snapshot().optString("status"));
        JSONArray posted = new JSONArray();
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() == McpService.MESSAGE_ID && notification.getPostTime() >= started) {
                posted.put(new JSONObject().put("event_id", notification.getTag()).put("posted_watch_ms", notification.getPostTime()));
            }
        }
        row.put("notifications", posted);
        row.put("idle_state", shell("dumpsys deviceidle"));
        row.put("wifi_state", shell("cmd wifi status"));
        row.put("battery_state", shell("dumpsys battery"));
        row.put("power_state", shell("dumpsys power"));
        row.put("routes_log", shell("logcat -d -v epoch -s WatchMcpNetwork:I WatchMcp:W '*:S'"));
        records.put(row);
        Files.write(new File(context.getFilesDir(), "long-sleep-test.json").toPath(), records.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = test.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
