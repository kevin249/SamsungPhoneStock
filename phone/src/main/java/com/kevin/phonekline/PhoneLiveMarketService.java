package com.kevin.phonekline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

/** User-enabled, screen-off quote subscription during the Shanghai trading sessions. */
public final class PhoneLiveMarketService extends Service {
    private static final String CHANNEL = "aod_live_market";
    private static final String DISABLE = "com.kevin.phonekline.DISABLE_LIVE_MARKET";
    private static final int NOTIFICATION = 0x4b22;
    // LockStar receives the latest frame once per second. Network sampling has its own clock.
    private static final long FRAME_MS = 1_000L;
    private static boolean streaming;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable guard = this::checkWindow;
    private final Runnable flush = this::publishLatest;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (settings, key) -> {
        if (key == null || key.equals("symbol") || key.equals("mcp_url")
                || key.equals("mcp_key_cipher") || key.equals("mcp_key_iv") || key.equals("paused")
                || key.equals("live_aod_enabled")) {
            // Discard queued frames before switching symbol, credentials or stopping.
            detach();
            main.removeCallbacks(guard);
            main.post(guard);
        }
    };
    private SharedPreferences prefs;
    private PowerManager.WakeLock wake;
    private PhoneQuoteSession session;
    private PhoneSamplingRate rates;
    private JSONObject pending, received;
    private long nextFrame;

    static boolean isStreaming() { return streaming; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("phone_dial", MODE_PRIVATE);
        rates = new PhoneSamplingRate(this,prefs,hz -> { if (session!=null) session.setRate(hz); });
        rates.start();
        prefs.registerOnSharedPreferenceChangeListener(changed);
        NotificationManager notices = getSystemService(NotificationManager.class);
        notices.createNotificationChannel(new NotificationChannel(CHANNEL, "盘中持续更新", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = getSystemService(PowerManager.class);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneKline:MarketSession");
        wake.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION, notice());
            }
        } catch (IllegalStateException | SecurityException unavailable) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && DISABLE.equals(intent.getAction())) {
            PhoneLiveSchedule.disable(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        PhoneLiveSchedule.armNext(this);
        checkWindow();
        return streaming ? START_STICKY : START_NOT_STICKY;
    }

    private void checkWindow() {
        main.removeCallbacks(guard);
        boolean enabled = PhoneLiveSchedule.enabled(this);
        if (!enabled || !PhoneLiveSchedule.inWindow(this)) {
            if (enabled) publishLatest();
            detach();
            stopSelf();
            return;
        }
        // Recheck at each whole minute and also in at most 30 s. A lost callback or
        // interrupted service cannot leave the CPU lock held beyond its timeout.
        long checkIn = Math.min(30_000L, 60_050L - System.currentTimeMillis() % 60_000L);
        wake.acquire(checkIn + 5_000L);
        main.postDelayed(guard, checkIn);
        if (session != null) return;
        String symbol = PhoneRealtimeCandles.normalize(prefs.getString("symbol", "sz000063"));
        if (symbol.isEmpty()) {
            stopSelf();
            return;
        }
        String address = prefs.getString("mcp_url", "");
        String key = address.isEmpty() ? "" : PhoneSecretStore.load(prefs);
        streaming = true;
        session = new PhoneQuoteSession(this::onQuote);
        session.start(symbol, address, key, rates.current(), PhoneMarketWidgetProvider.latest(this));
    }

    private void onQuote(JSONObject quote, String status) {
        if (quote == null || quote == received || !quote.has("bars")) return;
        boolean replay = received == null && "已缓存".equals(quote.optString("source"))
                && !quote.has("book") && !quote.has("fills");
        received = quote;
        if (replay) return;
        if (pending == null) {
            main.postAtTime(flush, Math.max(SystemClock.uptimeMillis(), nextFrame));
        }
        pending = quote;
    }

    private void publishLatest() {
        JSONObject data = pending;
        pending = null;
        if (data == null || !streaming || !prefs.getBoolean("live_aod_enabled", false)) return;
        PhoneMarketWidgetProvider.publishStream(this, data);
        nextFrame = SystemClock.uptimeMillis() + FRAME_MS;
    }

    private void detach() {
        streaming = false;
        main.removeCallbacks(flush);
        pending = received = null;
        nextFrame = 0;
        if (session != null) { session.close(); session = null; }
    }

    private Notification notice() {
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION,
                new Intent(this, PhoneDialActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), pendingFlags);
        PendingIntent disable = PendingIntent.getService(this, NOTIFICATION,
                new Intent(this, PhoneLiveMarketService.class).setAction(DISABLE), pendingFlags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_phone_dial)
                .setContentTitle("息屏行情正在更新")
                .setContentText("盘中持续更新息屏小组件，休市后转为系统定时刷新")
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "停止持续更新", disable).build())
                .build();
    }

    @Override public void onDestroy() {
        rates.stop();
        prefs.unregisterOnSharedPreferenceChangeListener(changed);
        publishLatest();
        detach();
        main.removeCallbacks(guard);
        if (wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        PhoneLiveSchedule.armNext(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
