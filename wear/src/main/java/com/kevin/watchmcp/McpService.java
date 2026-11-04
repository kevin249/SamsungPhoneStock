package com.kevin.watchmcp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONObject;

/** 用户开启的持续 MCP 订阅；屏幕关闭、Activity 销毁都不关闭服务。 */
public final class McpService extends Service {
    static final String ENABLED = "background_mcp_enabled";
    static final String CONNECTION_CHANNEL = "mcp_connection_v1";
    static final String MESSAGE_CHANNEL = "mcp_messages_v1";
    static final int CONNECTION_ID = 1001, MESSAGE_ID = 2001;
    private static final String STOP = "com.kevin.watchmcp.STOP_MCP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private McpHistory history;
    private NotificationManager notifications;
    private McpDirectClient client;
    private WifiNetworkPolicy networkPolicy;
    private String connectedUrl = "", connectedKey = "";
    private PowerManager.WakeLock processingLock;
    private int generation;
    private boolean testMode;
    private SharedPreferences dialPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener calendarChanges=(preferences,key) -> {
        if (DialCalendar.CACHE.equals(key)) {
            handler.removeCallbacks(this.dialTick);
            handler.post(this.dialTick);
        }
    };
    private final BroadcastReceiver screenChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(dialTick);
            handler.post(dialTick);
        }
    };
    private final Runnable dialTick = new Runnable() {
        @Override public void run() {
            boolean awake = getSystemService(PowerManager.class).isInteractive();
            boolean active=DialProvider.active(McpService.this);
            if (!testMode && active) {
                DialMarket.refresh(McpService.this);
                if (!awake) DialProvider.redraw(McpService.this);
            }
            java.time.LocalDateTime now=java.time.LocalDateTime.now(DialMarket.SHANGHAI);
            handler.postDelayed(this,DialRefreshPolicy.tickDelayMillis(now,
                    DialCalendar.day(dialPrefs,now.toLocalDate()),awake,active));
        }
    };

    static void ensureRunning(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("watch", MODE_PRIVATE);
        if (!prefs.getBoolean(ENABLED, true) || prefs.getString("mcp_url","").isEmpty() || SecretStore.load(prefs).isEmpty()) return;
        try { context.startForegroundService(new Intent(context, McpService.class)); }
        catch (RuntimeException error) { new McpHistory(prefs).status("后台服务启动失败 · 请打开应用重试"); }
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        createChannels();
        processingLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchMcp:message-processing");
        processingLock.setReferenceCounted(false);
        dialPrefs=getSharedPreferences("watch",MODE_PRIVATE);
        dialPrefs.registerOnSharedPreferenceChangeListener(calendarChanges);
        IntentFilter screens = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screens.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenChanges, screens);
        handler.post(dialTick);
    }

    private void createChannels() {
        NotificationChannel connection = new NotificationChannel(CONNECTION_CHANNEL, "MCP 后台连接", NotificationManager.IMPORTANCE_LOW);
        connection.setDescription("后台接收状态，保持安静");
        connection.enableVibration(false);
        connection.setSound(null, null);
        notifications.createNotificationChannel(connection);
        NotificationChannel messages = new NotificationChannel(MESSAGE_CHANNEL, "MCP 新消息震动", NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("收到新的 MCP 通知时震动提醒");
        messages.enableVibration(true);
        messages.setVibrationPattern(new long[]{0, 200, 100, 200});
        messages.setSound(null, null);
        messages.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notifications.createNotificationChannel(messages);
    }

    private PendingIntent openApp(boolean showNotifications) {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("show_notifications", showNotifications);
        return PendingIntent.getActivity(this, showNotifications ? 2 : 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification connectionNotification(String status) {
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, McpService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CONNECTION_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("腕上盯盘 · 后台接收 MCP").setContentText(status).setContentIntent(openApp(false))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "暂停接收", stop).build()).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences("watch", MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply();
            new McpHistory(getSharedPreferences("watch", MODE_PRIVATE)).status("后台接收已暂停");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean requestedTest = intent != null && intent.getBooleanExtra("test_mode", false)
                && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (prefs == null || requestedTest != testMode) {
            testMode = requestedTest;
            prefs = getSharedPreferences(testMode ? "mcp-service-test" : "watch", MODE_PRIVATE);
            history = new McpHistory(prefs);
        }
        Notification notification = connectionNotification(history.snapshot().optString("status", "正在连接，熄屏后继续接收"));
        if (Build.VERSION.SDK_INT >= 34) startForeground(CONNECTION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(CONNECTION_ID, notification);
        String url = prefs.getString("mcp_url", "");
        String key = SecretStore.load(prefs);
        if (!prefs.getBoolean(ENABLED, true) || url.isEmpty() || key.isEmpty()) {
            history.status(url.isEmpty() || key.isEmpty() ? "请设置手表 MCP 接口" : "后台接收已暂停");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean sameConfig = url.equals(connectedUrl) && key.equals(connectedKey);
        connectedUrl = url;
        connectedKey = key;
        if (testMode && networkPolicy != null) { networkPolicy.close(); networkPolicy = null; sameConfig = false; }
        if (!testMode && networkPolicy == null) {
            networkPolicy = new WifiNetworkPolicy(this, handler, this::restartClient);
            try { networkPolicy.start(); }
            catch (RuntimeException error) {
                history.status("网络策略启动失败 · 请重新打开应用");
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (client != null && sameConfig) { flushPending(); return START_STICKY; }
        restartClient();
        return START_STICKY;
    }

    private void restartClient() {
        final int identity = ++generation;
        if (client != null) { client.stop(); client = null; }
        if (!testMode && (networkPolicy == null || !networkPolicy.hasNetwork())) {
            history.status("等待可用网络 · Wi-Fi 优先");
            notifications.notify(CONNECTION_ID, connectionNotification("等待可用网络 · Wi-Fi 优先"));
            return;
        }
        client = new McpDirectClient(connectedUrl, connectedKey, new McpDirectClient.Listener() {
            public void status(String value) {
                handler.post(() -> {
                    if (identity != generation) return;
                    String route = networkPolicy == null ? "" : " · " + networkPolicy.transport();
                    String status = value.contains("已连接") ? "MCP 已连接" + route + " · 后台接收中" : value + route;
                    history.status(status);
                    notifications.notify(CONNECTION_ID, connectionNotification(status));
                });
            }
            public void resource(JSONObject payload) {
                handler.post(() -> {
                    if (identity != generation) return;
                    processingLock.acquire(15000);
                    try {
                        long before = history.snapshot().optLong("cursor");
                        history.merge(payload);
                        flushPending();
                        if (!testMode && before != history.snapshot().optLong("cursor")) DialProvider.redraw(McpService.this);
                    }
                    catch (Exception error) { history.status("消息保存失败 · 将自动重试"); }
                    finally { if (processingLock.isHeld()) processingLock.release(); }
                });
            }
        }, testMode ? url -> (java.net.HttpURLConnection) url.openConnection() : WifiNetworkPolicy::open);
        client.start();
        flushPending();
    }

    private void flushPending() {
        if (history == null || !notifications.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationChannel channel = notifications.getNotificationChannel(MESSAGE_CHANNEL);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        for (JSONObject row : history.pending()) {
            String id = row.optString("event_id");
            String title = row.optString("title");
            if (title.isEmpty()) title = "MCP 新消息";
            String body = row.optString("body");
            Notification notification = new Notification.Builder(this, MESSAGE_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openApp(true)).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).build();
            try {
                notifications.notify("mcp:" + id, MESSAGE_ID, notification);
                history.delivered(id);
            } catch (Exception error) { history.status("系统提醒暂不可用 · 消息已保存"); break; }
        }
    }

    @Override public void onDestroy() {
        generation++;
        if (client != null) client.stop();
        if (networkPolicy != null) networkPolicy.close();
        unregisterReceiver(screenChanges);
        dialPrefs.unregisterOnSharedPreferenceChangeListener(calendarChanges);
        handler.removeCallbacksAndMessages(null);
        if (processingLock != null && processingLock.isHeld()) processingLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
