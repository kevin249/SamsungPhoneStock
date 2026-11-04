package com.kevin.watchmcp;

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
