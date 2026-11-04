package com.kevin.watchmcp;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.app.NotificationManager;
import android.os.SystemClock;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 在真实 Wear OS 上检查 Keystore、HTTP 订阅、历史资源和 SSE 推送。 */
public class DirectClientInstrumentation extends Instrumentation {
    private boolean background;
    private boolean networkPolicy;
    private boolean longSleep;
    private boolean dial;
    private boolean dialEditor;
    private boolean dialDefaults;
    private boolean stockSearch;
    private boolean stockList;
    private boolean dialCalendar;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        background = arguments != null && "true".equals(arguments.getString("background"));
        networkPolicy = arguments != null && "true".equals(arguments.getString("network_policy"));
        longSleep = arguments != null && "true".equals(arguments.getString("long_sleep"));
        dial = arguments != null && "true".equals(arguments.getString("dial"));
        dialEditor = arguments != null && "true".equals(arguments.getString("dial_editor"));
        dialDefaults = arguments != null && "true".equals(arguments.getString("dial_defaults"));
        stockSearch = arguments != null && "true".equals(arguments.getString("stock_search"));
        stockList = arguments != null && "true".equals(arguments.getString("stock_list"));
        dialCalendar = arguments != null && "true".equals(arguments.getString("dial_calendar"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        McpDirectClient client = null;
        try {
            if (dialCalendar) {
                DialCalendarProbe.run(getTargetContext());
                result.putString("stream","PASS: calendar holidays, persisted open days, unknown future days, invalid response retains cache");
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (stockList) {
                new StockListProbe(this).run();
                result.putString("stream", "PASS: stock list UI, search, add, switch, direct removal, return; original watchlist restored");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (stockSearch) {
                StockSearchProbe.run();
                result.putString("stream", "PASS: search parsing/filtering, code, Chinese name, full/partial pinyin, initials, market suffix");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (dialDefaults) {
                new DialEditorProbe(this).selectDefaults();
                result.putString("stream","PASS: selected intraday, thousand-level distribution and MCP markers");
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (dialEditor) {
                new DialEditorProbe(this).run();
                result.putString("stream","PASS: native editor changed chart, depth and B/S options and restored all three");
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (dial) {
                DialRenderProbe.run(getTargetContext());
                result.putString("stream", "PASS: 09:30-15:00 replay, both chart modes, MCP B/S positions, order book toggle");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (longSleep) {
                new LongSleepProbe(this).run();
                result.putString("stream", "COMPLETE: passive screen-off and forced Doze; inspect long-sleep-test.json for message timing");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (networkPolicy) {
                new NetworkPolicyProbe(this).run();
                result.putString("stream", "PASS: Wi-Fi screen off, physical Wi-Fi loss to Bluetooth, Wi-Fi restored");
                finish(Activity.RESULT_OK, result);
                return;
            }
            checkHistory();
            if (background) {
                checkBackgroundService();
                result.putString("stream", "PASS: persistent history, screen off + forced Doze, system notification vibration channel, duplicate suppression");
                finish(Activity.RESULT_OK, result);
                return;
            }
            SharedPreferences prefs = getTargetContext().getSharedPreferences("native-test", 0);
            prefs.edit().clear().commit();
            SecretStore.save(prefs, "fixture-only");
            if (!"fixture-only".equals(SecretStore.load(prefs))) throw new AssertionError("Keystore round trip");
            if (prefs.getAll().toString().contains("fixture-only")) throw new AssertionError("Plaintext credential stored");
            CountDownLatch pushed = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> lastStatus = new java.util.concurrent.atomic.AtomicReference<>("");
            client = new McpDirectClient("http://127.0.0.1:9876/mcp", "fixture-only", new McpDirectClient.Listener() {
                public void status(String value) { lastStatus.set(value); }
                public void resource(JSONObject payload) {
                    reads.incrementAndGet();
                    JSONArray rows = payload.optJSONArray("notifications");
                    if (rows != null) for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row != null && "native-push".equals(row.optString("event_id"))) pushed.countDown();
                    }
                }
            });
            client.start();
            if (!pushed.await(20, TimeUnit.SECONDS)) throw new AssertionError("No SSE notification received; reads=" + reads.get() + "; " + lastStatus.get());
            if (reads.get() < 2) throw new AssertionError("Baseline was not followed by update");
            long stoppedAt = SystemClock.elapsedRealtime();
            client.stop();
            if (SystemClock.elapsedRealtime() - stoppedAt > 1000) throw new AssertionError("Stopping SSE blocked the caller");
            client = null;
            result.putString("stream", "Keystore encryption, discovery, authenticated HTTP, resource baseline and SSE update: PASS");
            prefs.edit().clear().commit();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        } finally { if (client != null) client.stop(); }
    }

    private void checkHistory() throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("history-test", 0);
        prefs.edit().clear().commit();
        McpHistory history = new McpHistory(prefs);
        JSONObject baseline = new JSONObject("{\"notifications\":[{\"event_id\":\"old\",\"sequence\":1}]}");
        history.merge(baseline);
        if (!history.pending().isEmpty()) throw new AssertionError("Historical messages alerted");
        JSONObject update = new JSONObject("{\"notifications\":[{\"event_id\":\"new\",\"sequence\":2},{\"event_id\":\"old\",\"sequence\":1}]}");
        history.merge(update);
        history = new McpHistory(prefs);
        history.merge(update);
        if (history.pending().size() != 1 || history.snapshot().optLong("cursor") != 2) throw new AssertionError("Persistent deduplication failed");
        history.delivered("new");
        history = new McpHistory(prefs);
        history.merge(update);
        if (!history.pending().isEmpty()) throw new AssertionError("Delivered message replayed");
        prefs.edit().clear().commit();
    }

    private void fixture(String path) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:9876/test/" + path).openConnection();
        try {
            connection.setRequestProperty("Authorization", "Bearer fixture-only");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) throw new AssertionError("Fixture control failed");
        } finally { connection.disconnect(); }
    }

    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             java.io.FileInputStream input = new java.io.FileInputStream(fd.getFileDescriptor())) {
            while (input.read() != -1) { }
        }
    }

    @android.annotation.SuppressLint("WearRecents") // Instrumentation 在非 Activity 上下文启动被测界面。
    private void checkBackgroundService() throws Exception {
        if (!getTargetContext().getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getTargetContext().getPackageName()))
            throw new AssertionError("Allow unrestricted background battery usage on watch first");
        NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) throw new AssertionError("Allow notification permission on watch first");
        SharedPreferences prefs = getTargetContext().getSharedPreferences("mcp-service-test", 0);
        prefs.edit().clear().putString("mcp_url", "http://127.0.0.1:9876/mcp").commit();
        SecretStore.save(prefs, "fixture-only");
        McpHistory history = new McpHistory(prefs);
        fixture("reset");
        Intent service = new Intent(getTargetContext(), McpService.class).putExtra("test_mode", true);
        boolean forcedIdle = false;
        try {
            startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> getTargetContext().startForegroundService(service));
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (history.snapshot().optLong("cursor") < 1 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            if (history.snapshot().optLong("cursor") != 1) throw new AssertionError("Service baseline missing: " + history.snapshot().optString("status"));
            fixture("push");
            shell("input keyevent KEYCODE_HOME");
            shell("input keyevent KEYCODE_SLEEP");
            forcedIdle = true;
            shell("dumpsys deviceidle force-idle");
            if (!getTargetContext().getSystemService(PowerManager.class).isDeviceIdleMode()) throw new AssertionError("Device did not enter forced Doze");
            if (getTargetContext().getSystemService(PowerManager.class).isInteractive()) throw new AssertionError("Screen did not turn off");
            deadline = SystemClock.elapsedRealtime() + 25000;
            while (history.snapshot().optLong("cursor") < 2 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            if (history.snapshot().optLong("cursor") != 2) throw new AssertionError("Screen-off background message missing: " + history.snapshot().optString("status"));
            SystemClock.sleep(2000);
            StatusBarNotification message = null;
            for (StatusBarNotification row : manager.getActiveNotifications()) if ("mcp:background-push".equals(row.getTag())) message = row;
            if (message == null) throw new AssertionError("System notification not posted");
            if (!manager.getNotificationChannel(message.getNotification().getChannelId()).shouldVibrate()) throw new AssertionError("Notification vibration disabled");
            if (!history.pending().isEmpty() || history.snapshot().optJSONArray("notifications").length() != 2) throw new AssertionError("Duplicate or undelivered messages");
        } finally {
            if (forcedIdle) shell("dumpsys deviceidle unforce");
            shell("input keyevent KEYCODE_WAKEUP");
            runOnMainSync(() -> getTargetContext().stopService(service));
            manager.cancel("mcp:background-push", McpService.MESSAGE_ID);
            prefs.edit().clear().commit();
        }
    }
}
