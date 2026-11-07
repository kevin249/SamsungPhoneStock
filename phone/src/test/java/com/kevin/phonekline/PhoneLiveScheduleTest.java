package com.kevin.phonekline;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class PhoneLiveScheduleTest {
    private final LocalDate friday = LocalDate.of(2026, 11, 6);

    @Test public void ambientDepthRunsInBothDozeStates() {
        assertTrue(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_DOZE));
        assertTrue(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_DOZE_SUSPEND));
        assertFalse(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_OFF));
        assertFalse(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_ON));
        assertFalse(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_UNKNOWN));
    }

    @Test public void stopAtLunchAndClose() {
        assertFalse(PhoneLiveSchedule.marketHours(friday.atTime(9, 29, 59)));
        assertTrue(PhoneLiveSchedule.marketHours(friday.atTime(9, 30)));
        assertTrue(PhoneLiveSchedule.marketHours(friday.atTime(11, 29, 59)));
        assertFalse(PhoneLiveSchedule.marketHours(friday.atTime(11, 30)));
        assertTrue(PhoneLiveSchedule.marketHours(friday.atTime(13, 0)));
        assertFalse(PhoneLiveSchedule.marketHours(friday.atTime(15, 0)));
        assertFalse(PhoneLiveSchedule.marketHours(friday.plusDays(1).atTime(10, 0)));
    }

    @Test public void nextAlarmSkipsLunchAndWeekend() {
        assertEquals(friday.atTime(9, 30), PhoneLiveSchedule.nextStart(friday.atStartOfDay()));
        assertEquals(friday.atTime(13, 0), PhoneLiveSchedule.nextStart(friday.atTime(11, 30)));
        assertEquals(friday.plusDays(3).atTime(9, 30), PhoneLiveSchedule.nextStart(friday.atTime(15, 0)));
        assertEquals(friday.plusDays(3).atTime(9, 30),
                PhoneLiveSchedule.nextStart(friday.plusDays(1).atTime(10, 0)));
    }
}
