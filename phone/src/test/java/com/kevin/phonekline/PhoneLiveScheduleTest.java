package com.kevin.phonekline;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class PhoneLiveScheduleTest {
    private final PhoneDisplayWindow window = new PhoneDisplayWindow(true,"realtime");
    private final LocalDate friday = LocalDate.of(2026, 11, 6);

    @Test public void ambientDepthRunsInBothDozeStates() {
        assertTrue(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_DOZE));
        assertTrue(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_DOZE_SUSPEND));
        assertFalse(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_OFF));
        assertFalse(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_ON));
        assertFalse(PhoneLiveSchedule.isAmbientState(android.view.Display.STATE_UNKNOWN));
    }

    @Test public void stopAtLunchAndClose() {
        assertFalse(PhoneLiveSchedule.marketHours(friday.atTime(9, 14, 59)));
        assertTrue(PhoneLiveSchedule.marketHours(friday.atTime(9, 15)));
        assertTrue(PhoneLiveSchedule.marketHours(friday.atTime(11, 29, 59)));
        assertFalse(PhoneLiveSchedule.marketHours(friday.atTime(11, 30)));
        assertTrue(PhoneLiveSchedule.marketHours(friday.atTime(13, 0)));
        assertFalse(PhoneLiveSchedule.marketHours(friday.atTime(15, 0)));
        assertFalse(PhoneLiveSchedule.marketHours(friday.plusDays(1).atTime(10, 0)));
    }

    @Test public void automaticPeriodIncludesAuctionsAndRespectsClosedDays() {
        assertEquals("1d", window.period(friday.atTime(9, 14, 59), false));
        assertEquals("realtime", window.period(friday.atTime(9, 15), false));
        assertEquals("realtime", window.period(friday.atTime(9, 25), false));
        assertEquals("realtime", window.period(friday.atTime(11, 30), false));
        assertEquals("realtime", window.period(friday.atTime(12, 30), false));
        assertEquals("realtime", window.period(friday.atTime(13, 0), false));
        assertEquals("realtime", window.period(friday.atTime(14, 57), false));
        assertEquals("1d", window.period(friday.atTime(15, 0), false));
        assertEquals("1d", window.period(friday.atTime(10, 0), true));
        assertEquals("1d", window.period(friday.plusDays(1).atTime(10, 0), false));
    }

    @Test public void displayTransitionsSkipLunchAndWeekend() {
        assertEquals(friday.atTime(9,15), window.nextTransition(friday.atTime(9,14),false));
        assertEquals(friday.atTime(15,0), window.nextTransition(friday.atTime(9,15),false));
        assertEquals(friday.atTime(15,0), window.nextTransition(friday.atTime(11,30),false));
        assertEquals(friday.atTime(15,0), window.nextTransition(friday.atTime(13,0),false));
        assertEquals(friday.plusDays(3).atTime(9,15), window.nextTransition(friday.atTime(15,0),false));
        assertEquals(friday.plusDays(3).atTime(9,15), window.nextTransition(friday.atTime(8,0),true));
    }

    @Test public void retryAnInterruptedSessionWithinItsScheduledWindow() {
        assertEquals(friday.atTime(9, 20),
                PhoneLiveSchedule.nextAlarm(window, friday.atTime(9, 15), false, true));
        assertEquals(friday.atTime(15, 0),
                PhoneLiveSchedule.nextAlarm(window, friday.atTime(14, 58), false, true));
        assertEquals(friday.atTime(15, 0),
                PhoneLiveSchedule.nextAlarm(window, friday.atTime(10, 0), false, false));
        assertEquals(friday.plusDays(3).atTime(9, 15),
                PhoneLiveSchedule.nextAlarm(window, friday.atTime(10, 0), true, true));
        assertEquals(friday.plusDays(3).atTime(9, 15),
                PhoneLiveSchedule.nextAlarm(window, friday.atTime(15, 0), false, true));
    }

    @Test public void manualModesRemainSelectedThroughoutTheDayWhenAutomaticIsOff() {
        for (String mode : new String[]{"realtime","1m","1d"}) {
            PhoneDisplayWindow manual = new PhoneDisplayWindow(false,mode);
            for (int hour : new int[]{8,10,12,16,23}) {
                assertEquals(mode,manual.period(friday.atTime(hour,0),false));
                assertEquals(mode,manual.period(friday.atTime(hour,0),true));
            }
            assertEquals(mode,manual.period(friday.plusDays(1).atTime(10,0),false));
            assertFalse(manual.includes(friday.atTime(16,0))); // Selecting a chart never enables off-hours networking.
        }
    }

    @Test public void automaticModeOverridesButDoesNotForgetTheManualChoice() {
        PhoneDisplayWindow automatic = new PhoneDisplayWindow(true,"1m");
        assertEquals("1m",automatic.manualMode);
        assertEquals("realtime",automatic.period(friday.atTime(12,0),false));
        assertEquals("1d",automatic.period(friday.atTime(16,0),false));
        assertEquals("1m",new PhoneDisplayWindow(false,automatic.manualMode).period(friday.atTime(16,0),false));
    }
}
