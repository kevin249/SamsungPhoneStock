package com.kevin.watchmcp;

import org.junit.Test;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DialRefreshPolicyTest {
    // 实际 TDX 日历截面；国庆、中秋和调休周末均未列为开市日。
    private static final TradingCalendar CALENDAR=new TradingCalendar(LocalDate.parse("2026-09-20"),LocalDate.parse("2026-11-10"),
            Arrays.asList("20260921","20260922","20260923","20260924","20260928","20260929","20260930",
                    "20261008","20261009","20261012","20261013","20261014","20261015","20261016",
                    "20261019","20261020","20261021","20261022","20261023","20261026","20261027",
                    "20261028","20261029","20261030","20261102","20261103","20261104"));
    private static boolean trading(String stamp) {
        LocalDateTime now=LocalDateTime.parse(stamp);
        return DialRefreshPolicy.isTradingWindow(now,CALENDAR.day(now.toLocalDate()));
    }
    private static long delay(String stamp, boolean awake, boolean active) {
        LocalDateTime now=LocalDateTime.parse(stamp);
        return DialRefreshPolicy.tickDelayMillis(now,CALENDAR.day(now.toLocalDate()),awake,active);
    }
    @Test public void fastRefreshStopsExactlyAtLunchAndClose() {
        for (String clock:new String[]{"09:30:00","11:29:59.999","13:00:00","14:59:59.999"})
            assertTrue(clock,trading("2026-11-04T"+clock));
        for (String clock:new String[]{"09:29:59.999","11:30:00","11:30:59","12:59:59.999","15:00:00","15:00:59","18:00:00"})
            assertFalse(clock,trading("2026-11-04T"+clock));
        assertFalse(trading("2026-11-07T10:00:00"));
        assertFalse(trading("2026-11-08T14:00:00"));
    }

    @Test public void actualHolidaysAndMakeupWeekendsNeverUseOneSecondPolling() {
        for (String date:new String[]{"2026-09-25","2026-10-01","2026-10-05","2026-10-07","2026-10-10"}) {
            assertEquals(TradingCalendar.Day.CLOSED,CALENDAR.day(LocalDate.parse(date)));
            for (String clock:new String[]{"09:30:00","10:00:00","13:00:00","14:59:59"}) {
                assertFalse(date+"T"+clock,trading(date+"T"+clock));
                assertEquals(30000,delay(date+"T"+clock,true,true));
            }
        }
        assertTrue(trading("2026-10-08T10:00:00"));
        assertTrue(trading("2026-09-28T14:00:00"));
    }

    @Test public void unconfirmedFutureWeekdayCannotFallBackToMondayThroughFriday() {
        assertEquals(TradingCalendar.Day.UNKNOWN,CALENDAR.day(LocalDate.parse("2026-11-05")));
        assertFalse(trading("2026-11-05T10:00:00"));
        assertEquals(30000,delay("2026-11-05T10:00:00",true,true));
    }

    @Test public void wakingAfterCloseDoesNotRestoreOneSecondRequests() {
        assertEquals(1000,DialRefreshPolicy.marketInterval(true,true,true));
        assertEquals(29000,DialRefreshPolicy.marketInterval(true,false,true));
        assertEquals(900000,DialRefreshPolicy.marketInterval(false,true,true));
        assertEquals(900000,DialRefreshPolicy.marketInterval(false,false,true));
        assertEquals(60000,DialRefreshPolicy.marketInterval(false,true,false));
    }

    @Test public void schedulerSleepsOutsideSessionButWakesAtOpening() {
        assertEquals(30000,delay("2026-11-04T12:00:00",true,true));
        assertEquals(30000,delay("2026-11-04T18:00:00",true,true));
        assertEquals(250,delay("2026-11-04T09:29:59.750",true,true));
        assertEquals(10000,delay("2026-11-04T12:59:50",true,true));
        assertEquals(1000,delay("2026-11-04T13:00:00",true,true));
        assertEquals(30000,delay("2026-10-10T09:29:59",true,true));
        assertEquals(30000,delay("2026-11-04T10:00:00",false,true));
        assertEquals(30000,delay("2026-11-04T10:00:00",true,false));
    }
}
