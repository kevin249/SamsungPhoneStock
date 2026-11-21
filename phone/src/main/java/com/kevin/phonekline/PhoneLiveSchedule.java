package com.kevin.phonekline;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.time.LocalDateTime;

/** Schedules user-enabled market sessions in Shanghai time, independent of the phone's time zone. */
public final class PhoneLiveSchedule extends BroadcastReceiver {
    private static final String WAKE = "com.kevin.phonekline.START_MARKET_WINDOW";
    private static DisplayManager observedDisplays;
    private static DisplayManager.DisplayListener displayListener;

    static boolean ambientDisplay(Context context) {
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        return display != null && isAmbientState(display.getState());
    }

    static boolean isAmbientState(int state) {
        return state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
    }

    static boolean shouldStream(Context context) {
        return inWindow(context);
    }

    static boolean canStartSession(Context context) {
        return displayHours(context) && !PhoneLiveTradingDay.closed(context);
    }

    // A widget-only installation also needs the screen transition, without a visible Activity.
    private static void observeDisplay(Context context, boolean enabled) {
        if (!enabled) {
            if (observedDisplays != null) observedDisplays.unregisterDisplayListener(displayListener);
            observedDisplays = null;
            displayListener = null;
            return;
        }
        if (observedDisplays != null) return;
        Context app = context.getApplicationContext();
        observedDisplays = app.getSystemService(DisplayManager.class);
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { syncFromDisplay(app); }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY) syncFromDisplay(app);
            }
        };
        observedDisplays.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
    }

    static void syncFromDisplay(Context context) {
        if (enabled(context) && canStartSession(context)) {
            if (shouldStream(context) && !PhoneLiveMarketService.isStreaming()) start(context);
        } else context.stopService(new Intent(context, PhoneLiveMarketService.class));
    }

    // Actual exchange trading hours remain separate from the configurable display window.
    static boolean marketHours(LocalDateTime now) {
        java.time.DayOfWeek day = now.getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) return false;
        java.time.LocalTime t = now.toLocalTime();
        return !t.isBefore(java.time.LocalTime.of(9,15)) && t.isBefore(java.time.LocalTime.of(11,30))
                || !t.isBefore(java.time.LocalTime.of(13,0)) && t.isBefore(java.time.LocalTime.of(15,0));
    }

    static PhoneDisplayWindow window(Context context) {
        return PhoneDisplayWindow.read(context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE));
    }

    static boolean displayHours(Context context) { return displayHours(context, PhoneRealtimeCandles.now()); }

    static boolean displayHours(Context context, LocalDateTime now) { return window(context).includes(now); }

    static boolean inWindow(Context context) {
        return displayHours(context) && PhoneLiveTradingDay.confirmed(context);
    }

    static String displayPeriod(Context context) { return displayPeriod(context, PhoneRealtimeCandles.now()); }

    static String displayPeriod(Context context, LocalDateTime now) {
        return window(context).period(now, PhoneLiveTradingDay.closed(context));
    }

    static boolean canSchedule(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static boolean enabled(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        return prefs.getBoolean("live_aod_enabled", false) && !prefs.getBoolean("paused", false)
                && PhoneMarketWidgetProvider.hasWidgets(c);
    }

    private static PendingIntent intent(Context c) {
        return PendingIntent.getBroadcast(c, 761, new Intent(c, PhoneLiveSchedule.class).setAction(WAKE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void setAlarm(Context c, LocalDateTime at) {
        if (!canSchedule(c)) return;
        try {
            c.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    at.atZone(PhoneRealtimeCandles.SHANGHAI).toInstant().toEpochMilli(), intent(c));
        } catch (SecurityException permissionRevoked) {
            // Opening the app can start a confirmed session without a scheduled alarm.
        }
    }

    static void armNext(Context c) {
        boolean active = PhoneMarketWidgetProvider.hasWidgets(c)
                && !c.getSharedPreferences("phone_dial", Context.MODE_PRIVATE).getBoolean("paused", false);
        observeDisplay(c, enabled(c));
        if (!active) {
            c.getSystemService(AlarmManager.class).cancel(intent(c));
            return;
        }
        LocalDateTime now = PhoneRealtimeCandles.now();
        boolean needsRetry = !PhoneLiveTradingDay.confirmed(c)
                || enabled(c) && !PhoneLiveMarketService.isStreaming();
        setAlarm(c, nextAlarm(window(c), now, PhoneLiveTradingDay.closed(c), needsRetry));
    }

    static LocalDateTime nextAlarm(PhoneDisplayWindow window, LocalDateTime now, boolean closed, boolean needsRetry) {
        LocalDateTime next = window.nextTransition(now, closed);
        if (needsRetry && !closed && window.includes(now)) {
            LocalDateTime retry = now.plusMinutes(5);
            if (retry.isBefore(next)) next = retry;
        }
        return next;
    }

    static void disable(Context c) {
        c.getSharedPreferences("phone_dial", Context.MODE_PRIVATE).edit().putBoolean("live_aod_enabled", false).apply();
        c.getSystemService(AlarmManager.class).cancel(intent(c));
        c.stopService(new Intent(c, PhoneLiveMarketService.class));
        observeDisplay(c, false);
    }

    static void syncFromActivity(android.app.Activity activity) {
        armNext(activity);
        if (enabled(activity) && canStartSession(activity)) {
            if (!PhoneLiveMarketService.isStreaming()) start(activity);
        } else activity.stopService(new Intent(activity, PhoneLiveMarketService.class));
    }

    private static void start(Context c) {
        try { c.startForegroundService(new Intent(c, PhoneLiveMarketService.class)); }
        catch (IllegalStateException | SecurityException denied) {
            // A revoked permission or transient background-start restriction cannot start a data session.
            armNext(c);
        }
    }

    @Override public void onReceive(Context context, Intent message) {
        Context app = context.getApplicationContext();
        armNext(app);
        boolean alarm = WAKE.equals(message.getAction());
        boolean live = enabled(app) && canStartSession(app);
        if (live && !PhoneLiveMarketService.isStreaming()) {
            // Use the exact alarm's foreground-start exemption before starting calendar I/O.
            if (alarm) start(app);
            else setAlarm(app, PhoneRealtimeCandles.now().plusSeconds(1));
        } else if (!live) syncFromDisplay(app);
        PhoneMarketWidgetProvider.updateTheme(app);
        if (live || PhoneLiveTradingDay.confirmed(app) || !canStartSession(app)
                || !PhoneMarketWidgetProvider.hasWidgets(app)
                || app.getSharedPreferences("phone_dial", Context.MODE_PRIVATE).getBoolean("paused", false)) {
            PhoneWidgetRefreshService.schedule(app, true);
            return;
        }
        if (!alarm) {
            setAlarm(app, PhoneRealtimeCandles.now().plusSeconds(1));
            PhoneWidgetRefreshService.schedule(app, true);
            return;
        }
        // The snapshot-only widget still needs to resolve the trading day at the opening alarm.
        PendingResult pending = goAsync();
        PowerManager.WakeLock lock = app.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonekline:market-calendar");
        lock.acquire(30_000);
        PhoneLiveTradingDay.refresh(app, () -> {
            try {
                armNext(app);
                PhoneMarketWidgetProvider.updateTheme(app);
                PhoneWidgetRefreshService.schedule(app, true);
            } finally {
                if (lock.isHeld()) lock.release();
                pending.finish();
            }
        });
    }
}
