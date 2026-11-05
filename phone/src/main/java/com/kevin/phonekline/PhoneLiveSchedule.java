package com.kevin.phonekline;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Schedules user-enabled market sessions in Shanghai time, independent of the phone's time zone. */
public final class PhoneLiveSchedule extends BroadcastReceiver {
    private static final String WAKE = "com.kevin.phonekline.START_MARKET_WINDOW";
    private static final LocalTime OPEN = LocalTime.of(9, 30), LUNCH = LocalTime.of(11, 30);
    private static final LocalTime REOPEN = LocalTime.of(13, 0), CLOSE = LocalTime.of(15, 0);

    private static boolean weekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static boolean marketHours(LocalDateTime now) {
        LocalTime t = now.toLocalTime();
        return weekday(now.toLocalDate()) && ((!t.isBefore(OPEN) && t.isBefore(LUNCH))
                || (!t.isBefore(REOPEN) && t.isBefore(CLOSE)));
    }

    static LocalDateTime nextStart(LocalDateTime now) {
        LocalDate d = now.toLocalDate();
        if (weekday(d)) {
            if (now.toLocalTime().isBefore(OPEN)) return d.atTime(OPEN);
            if (now.toLocalTime().isBefore(REOPEN)) return d.atTime(REOPEN);
        }
        do { d = d.plusDays(1); } while (!weekday(d));
        return d.atTime(OPEN);
    }

    static boolean inWindow(Context context) {
        return marketHours(PhoneRealtimeCandles.now()) && PhoneLiveTradingDay.confirmed(context);
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
        if (!enabled(c)) {
            c.getSystemService(AlarmManager.class).cancel(intent(c));
            return;
        }
        LocalDateTime now = PhoneRealtimeCandles.now(), next = nextStart(now);
        if (PhoneLiveTradingDay.closed(c)) next = nextStart(now.toLocalDate().atTime(CLOSE));
        else if (marketHours(now) && !PhoneLiveTradingDay.confirmed(c)) next = now.plusMinutes(5);
        setAlarm(c, next);
    }

    static void disable(Context c) {
        c.getSharedPreferences("phone_dial", Context.MODE_PRIVATE).edit().putBoolean("live_aod_enabled", false).apply();
        c.getSystemService(AlarmManager.class).cancel(intent(c));
        c.stopService(new Intent(c, PhoneLiveMarketService.class));
    }

    static void syncFromActivity(android.app.Activity activity) {
        armNext(activity);
        if (!enabled(activity) || !marketHours(PhoneRealtimeCandles.now())) return;
        if (PhoneLiveTradingDay.confirmed(activity)) start(activity);
        else PhoneLiveTradingDay.refresh(activity, () -> {
            if (!enabled(activity) || !inWindow(activity)) return;
            if (!activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()) start(activity);
            else setAlarm(activity, PhoneRealtimeCandles.now().plusSeconds(1));
        });
    }

    private static void start(Context c) {
        try { c.startForegroundService(new Intent(c, PhoneLiveMarketService.class)); }
        catch (IllegalStateException | SecurityException denied) {
            // A revoked permission or transient background-start restriction cannot start a data session.
            if (enabled(c) && marketHours(PhoneRealtimeCandles.now())) setAlarm(c, PhoneRealtimeCandles.now().plusMinutes(5));
        }
    }

    @Override public void onReceive(Context context, Intent message) {
        Context app = context.getApplicationContext();
        armNext(app);
        if (!enabled(app) || !marketHours(PhoneRealtimeCandles.now())) return;
        if (!WAKE.equals(message.getAction())) {
            setAlarm(app, PhoneRealtimeCandles.now().plusSeconds(1));
            return;
        }
        if (inWindow(app)) { start(app); return; }
        PendingResult pending = goAsync();
        PowerManager.WakeLock lock = app.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonekline:market-calendar");
        lock.acquire(30_000);
        PhoneLiveTradingDay.refresh(app, () -> {
            try {
                armNext(app);
                if (enabled(app) && inWindow(app)) start(app);
            } finally {
                if (lock.isHeld()) lock.release();
                pending.finish();
            }
        });
    }
}
