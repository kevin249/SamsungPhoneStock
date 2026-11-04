package com.kevin.watchmcp;

import org.junit.Test;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class TradingCalendarTest {
    private static final LocalDate START=LocalDate.of(2026,9,30), END=LocalDate.of(2026,11,30);

    @Test public void requestedFutureRangeIsNotMistakenForCompleteCoverage() {
        TradingCalendar days=new TradingCalendar(START,END,Arrays.asList("20260930","20261008"));
        assertEquals(TradingCalendar.Day.OPEN,days.day(LocalDate.of(2026,10,8)));
        assertEquals(TradingCalendar.Day.CLOSED,days.day(LocalDate.of(2026,10,1)));
        assertEquals(TradingCalendar.Day.UNKNOWN,days.day(LocalDate.of(2026,10,9)));
        assertEquals(TradingCalendar.Day.UNKNOWN,days.day(START.minusDays(1)));
        assertEquals(TradingCalendar.Day.UNKNOWN,new TradingCalendar(START,END,Collections.emptyList()).day(START));
    }

    @Test public void invalidOrPartialDatesAreRejected() {
        assertThrows(RuntimeException.class,()->new TradingCalendar(START,END,Arrays.asList("20260230")));
        assertThrows(RuntimeException.class,()->new TradingCalendar(START,END,Arrays.asList("20270104")));
        assertThrows(RuntimeException.class,()->new TradingCalendar(START,END,Arrays.asList("20261008","20261008")));
        assertThrows(RuntimeException.class,()->new TradingCalendar(END,START,Collections.emptyList()));
    }
}
