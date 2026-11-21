package com.kevin.watchmcp;

import com.kevin.market.TradingCalendar;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 上海时间的日内刷新窗口；历史图表仍可保留 11:30、15:00 的收盘数据点。 */
final class DialRefreshPolicy {
    private static final LocalTime MORNING_OPEN=LocalTime.of(9,30), MORNING_CLOSE=LocalTime.of(11,30);
    private static final LocalTime AFTERNOON_OPEN=LocalTime.of(13,0), AFTERNOON_CLOSE=LocalTime.of(15,0);

    static boolean isTradingWindow(LocalDateTime now, TradingCalendar.Day day) {
        return day==TradingCalendar.Day.OPEN && isSessionTime(now);
    }

    static boolean isSessionTime(LocalDateTime now) {
        LocalTime time=now.toLocalTime();
        return !time.isBefore(MORNING_OPEN) && time.isBefore(MORNING_CLOSE)
                || !time.isBefore(AFTERNOON_OPEN) && time.isBefore(AFTERNOON_CLOSE);
    }

    static long marketInterval(boolean trading, boolean awake, boolean hasBars) {
        if (trading) return awake ? 1000 : 29000;
        return hasBars ? 900000 : 60000;
    }

    // 熄屏按实际经过的时间预约。跨夜先核对次日的日历，只有已确认开市才每 30 秒更新。
    static long screenOffWakeDelay(LocalDateTime now, TradingCalendar.Day day, boolean active) {
        if (!active) return -1;
        LocalTime time = now.toLocalTime();
        LocalTime checkCalendar = MORNING_OPEN.minusMinutes(1);
        if (day == TradingCalendar.Day.CLOSED || !time.isBefore(AFTERNOON_CLOSE)) {
            return Duration.between(now, now.toLocalDate().plusDays(1).atTime(checkCalendar)).toMillis();
        }
        if (day == TradingCalendar.Day.UNKNOWN && time.isBefore(checkCalendar))
            return Duration.between(time, checkCalendar).toMillis();
        if (time.isBefore(MORNING_OPEN)) return Duration.between(time, MORNING_OPEN).toMillis();
        long interval = day == TradingCalendar.Day.OPEN ? 30000 : 300000;
        if (time.isBefore(MORNING_CLOSE)) return Math.min(interval, Duration.between(time, MORNING_CLOSE).toMillis());
        if (time.isBefore(AFTERNOON_OPEN)) return Duration.between(time, AFTERNOON_OPEN).toMillis();
        return Math.min(interval, Duration.between(time, AFTERNOON_CLOSE).toMillis());
    }

    static LocalDateTime latestCompletedMinute(LocalDateTime now, TradingCalendar.Day day) {
        LocalTime time = now.toLocalTime();
        if (day != TradingCalendar.Day.OPEN || time.isBefore(MORNING_OPEN)) return null;
        if (!time.isBefore(AFTERNOON_CLOSE)) time = AFTERNOON_CLOSE;
        else if (!time.isBefore(MORNING_CLOSE) && time.isBefore(AFTERNOON_OPEN)) time = MORNING_CLOSE;
        return now.toLocalDate().atTime(time.withSecond(0).withNano(0));
    }

    static long tickDelayMillis(LocalDateTime now, TradingCalendar.Day day, boolean awake, boolean active) {
        if (awake && active && isTradingWindow(now,day)) return 1000;
        long delay=30000;
        // 盘前和午休不每秒唤醒，但跨过开盘时刻时不多等一个低频周期。
        if (awake && active && day!=TradingCalendar.Day.CLOSED) {
            for (LocalTime opening:new LocalTime[]{MORNING_OPEN,AFTERNOON_OPEN}) {
                long remaining=Duration.between(now,now.toLocalDate().atTime(opening)).toMillis();
                if (remaining>0) delay=Math.min(delay,remaining);
            }
        }
        return delay;
    }
}
