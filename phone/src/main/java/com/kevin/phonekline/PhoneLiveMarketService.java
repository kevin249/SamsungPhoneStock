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
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** User-enabled market subscription, including depth while the ambient widget is visible. */
public final class PhoneLiveMarketService extends Service {
    private static final String CHANNEL = "aod_live_market";
    private static final String DISABLE = "com.kevin.phonekline.DISABLE_LIVE_MARKET";
    private static final int NOTIFICATION = 0x4b22;
    // LockStar receives the latest frame once per second. Network sampling has its own clock.
    private static final long FRAME_MS = 1_000L;
    private static final long WAKE_TIMEOUT_MS = 120_000L;
    private static boolean streaming;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable guard = this::checkWindow;
    private final Runnable flush = this::publishLatest;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (settings, key) -> {
        if (key != null && key.startsWith(PhoneDisplayStocks.HEIGHT_PREFIX)) {
            main.removeCallbacks(guard);
            main.post(guard);
            return;
        }
        if (key == null || key.equals("symbol") || key.equals("second_symbol") || key.equals(PhoneDisplayStocks.ORDER)
                || key.equals(PhoneDisplayStocks.MINI) || key.startsWith(PhoneWidgetRenderer.PAGE) || key.equals("mcp_url")
                || key.equals("mcp_key_cipher") || key.equals("mcp_key_iv") || key.equals("paused")
                || key.equals("live_aod_enabled") || key.equals(PhoneDisplayWindow.AUTOMATIC) || key.equals(PhoneDisplayWindow.MODE) || key.equals("live_calendar_date") || key.equals("live_calendar_open")) {
            // Discard queued frames before switching symbol, credentials or stopping.
            detach();
            main.removeCallbacks(guard);
            main.post(guard);
        }
    };
    private SharedPreferences prefs;
    private PowerManager.WakeLock wake;
    private final Map<String, PhoneDisplayFeed> sessions = new LinkedHashMap<>();
    private String displayPeriod = "";
    private PhoneSamplingRate rates;
    private final Map<String, JSONObject> pending = new LinkedHashMap<>(), received = new LinkedHashMap<>();
    private long nextFrame;
    private long submittedFrames, lastSubmittedAt;
    private boolean checkingDay, stopping;

    static boolean isStreaming() { return streaming; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("phone_dial", MODE_PRIVATE);
        rates = new PhoneSamplingRate(this,prefs,hz -> {
            for (PhoneDisplayFeed session : sessions.values()) session.setRate(hz);
        });
        rates.start();
        prefs.registerOnSharedPreferenceChangeListener(changed);
        NotificationManager notices = getSystemService(NotificationManager.class);
        notices.createNotificationChannel(new NotificationChannel(CHANNEL, "息屏行情持续更新", NotificationManager.IMPORTANCE_LOW));
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
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && DISABLE.equals(intent.getAction())) {
            stopping = true;
            PhoneLiveSchedule.disable(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        stopping = false;
        PhoneLiveSchedule.armNext(this);
        checkWindow();
        return stopping ? START_NOT_STICKY : START_STICKY;
    }

    private void checkWindow() {
        main.removeCallbacks(guard);
        if (stopping) return;
        boolean enabled = PhoneLiveSchedule.enabled(this);
        if (!enabled || !PhoneLiveSchedule.canStartSession(this)) {
            stopMarket(enabled);
            return;
        }
        // Leave room for delayed main-thread work while keeping a timeout if the service stalls.
        wake.acquire(WAKE_TIMEOUT_MS);
        if (!PhoneLiveSchedule.shouldStream(this)) {
            detach();
            if (!checkingDay) {
                checkingDay = true;
                PhoneLiveTradingDay.refresh(this, () -> {
                    checkingDay = false;
                    if (stopping) return;
                    if (PhoneLiveSchedule.shouldStream(this)) checkWindow();
                    else stopMarket(PhoneLiveSchedule.enabled(this));
                });
            }
            return;
        }
        long checkIn = Math.min(30_000L, 60_050L - System.currentTimeMillis() % 60_000L);
        main.postDelayed(guard, checkIn);
        String period = PhoneLiveSchedule.displayPeriod(this);
        Set<String> visible = PhoneMarketWidgetProvider.visibleStocks(this);
        if (visible.isEmpty()) { stopMarket(false); return; }
        if (sessions.keySet().equals(visible) && period.equals(displayPeriod)) return;
        detach();
        displayPeriod = period;
        PhoneMarketWidgetProvider.updateTheme(this);
        streaming = true;
        PhoneLiveSchedule.armNext(this);
        Set<String> minis = PhoneDisplayStocks.minis(prefs);
        for (String code : visible) {
            PhoneDisplayFeed session = new PhoneDisplayFeed(prefs, (quote, status) -> onQuote(code, quote));
            sessions.put(code, session);
            session.start(code, PhoneDisplayStocks.period(minis, code, period), rates.current());
        }
    }

    private void stopMarket(boolean renderCached) {
        stopping = true;
        if (renderCached) {
            publishLatest();
            PhoneMarketWidgetProvider.updateTheme(this);
            PhoneWidgetRefreshService.schedule(this, true);
        }
        detach();
        stopSelf();
    }

    private void onQuote(String selected, JSONObject quote) {
        if (!PhoneLiveSchedule.shouldStream(this)) { checkWindow(); return; }
        JSONObject previous = received.get(selected);
        if (quote == null || quote == previous || !quote.has("bars") || !selected.equals(quote.optString("symbol"))) return;
        boolean replay = previous == null && "已缓存".equals(quote.optString("source"))
                && !quote.has("book") && !quote.has("fills");
        received.put(selected, quote);
        if (replay) return;
        if (pending.isEmpty()) {
            main.postAtTime(flush, Math.max(SystemClock.uptimeMillis(), nextFrame));
        }
        pending.put(selected, quote);
    }

    private void publishLatest() {
        if (pending.isEmpty() || !streaming || !prefs.getBoolean("live_aod_enabled", false)) return;
        PhoneMarketWidgetProvider.publishBatch(this, pending.values(), true);
        pending.clear();
        submittedFrames++;
        lastSubmittedAt = SystemClock.elapsedRealtime();
        nextFrame = SystemClock.uptimeMillis() + FRAME_MS;
    }

    private void detach() {
        streaming = false;
        main.removeCallbacks(flush);
        pending.clear();
        received.clear();
        nextFrame = 0;
        for (PhoneDisplayFeed session : sessions.values()) session.stop();
        sessions.clear();
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
                .setContentText("集合竞价及盘中自动更新，休市保留日 K 并停止后台请求")
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "停止持续更新", disable).build())
                .build();
    }

    @Override public void onDestroy() {
        stopping = true;
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

    @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        PowerManager power = getSystemService(PowerManager.class);
        out.println("streaming=" + streaming + " ambient=" + PhoneLiveSchedule.ambientDisplay(this)
                + " rate_hz=" + rates.current() + " cpu_lock=" + wake.isHeld()
                + " checking_day=" + checkingDay + " idle=" + power.isDeviceIdleMode()
                + " battery_exempt=" + power.isIgnoringBatteryOptimizations(getPackageName()));
        out.println("submitted_frames=" + submittedFrames + " last_submit_age_ms="
                + (lastSubmittedAt == 0 ? -1 : SystemClock.elapsedRealtime() - lastSubmittedAt));
        for (Map.Entry<String, PhoneDisplayFeed> stock : sessions.entrySet()) {
            PhoneQuoteSession.RateSample sample = stock.getValue().sample();
            JSONObject quote = received.get(stock.getKey());
            JSONObject book = quote == null ? null : quote.optJSONObject("book");
            out.println("symbol=" + stock.getKey() + " trade_sent=" + sample.tradeSentTotal + " trade_ok=" + sample.tradeOkTotal
                    + " depth_sent=" + sample.depthSentTotal + " depth_ok=" + sample.depthOkTotal
                    + " book_at=" + (book == null ? "" : book.optString("source_at")));
        }
    }
}
