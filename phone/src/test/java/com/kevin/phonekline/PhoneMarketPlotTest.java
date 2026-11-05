package com.kevin.phonekline;

import static org.junit.Assert.*;
import java.time.LocalDateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class PhoneMarketPlotTest {
    private static final String DAY="2026-11-05", SYMBOL="sz000063";

    private static JSONObject bar(String clock, double price) throws Exception {
        return new JSONObject().put("time",DAY+" "+clock).put("open",price-.1).put("high",price+.2)
                .put("low",price-.2).put("close",price);
    }

    private static JSONObject snapshot(JSONArray bars) throws Exception {
        return new JSONObject().put("date",DAY).put("symbol",SYMBOL).put("bars",bars);
    }

    @Test public void tdwUsesIndependentTwentyBarFiltersBeforeTheVisibleWindow() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 85; i++) {
            String clock = java.time.LocalTime.of(9,31).plusMinutes(i).toString();
            boolean peak = i == 24 || i == 29 || i == 45;
            int close = peak ? 20 : 10;
            rows.put(bar(clock,close).put("open",close).put("low",10).put("high",20));
        }
        PhoneMarketPlot session = new PhoneMarketPlot(snapshot(rows), true, false, false);
        assertEquals(1.6,session.tdwAt(24),1e-9);
        assertEquals(3,session.tdwEventsAt(24)); // crossed both .2 and .5
        assertEquals(0,session.tdwEventsAt(29));
        assertEquals(3,session.tdwEventsAt(45)); // eligible on bar 21
        PhoneMarketPlot recent = new PhoneMarketPlot(snapshot(rows));
        assertEquals(session.tdwAt(45),recent.tdwAt(20),1e-9);
        assertEquals(3,recent.tdwEventsAt(20));
    }

    @Test public void fullScreenPriceAxisUsesTheDailyCornerPricesInBothModes() throws Exception {
        JSONObject data = snapshot(new JSONArray().put(bar("14:30",263.15)))
                .put("high",272.99).put("low",259.00);
        for (boolean intraday : new boolean[]{false,true}) {
            PhoneMarketPlot plot = new PhoneMarketPlot(data,intraday,false,true);
            assertEquals(20f,plot.priceY(272.99,420,400),.001f);
            assertEquals(420f,plot.priceY(259.00,420,400),.001f);
        }
    }

    @Test public void exchangeAxisIsFixedAndExcludesNonTradingMinutes() {
        assertEquals(0,PhoneMarketPlot.sessionMinute("09:30"));
        assertEquals(120,PhoneMarketPlot.sessionMinute("11:30"));
        assertEquals(120,PhoneMarketPlot.sessionMinute("13:00"));
        assertEquals(121,PhoneMarketPlot.sessionMinute("13:01"));
        assertEquals(240,PhoneMarketPlot.sessionMinute("15:00"));
        for (String time : new String[]{"09:29","11:31","12:07","12:59","15:01","09:71","95:00","hh:mm"})
            assertEquals(time,-1,PhoneMarketPlot.sessionMinute(time));
    }

    @Test public void fullDayLineRestartsAfterLunchAndOnMissingData() throws Exception {
        JSONArray rows=new JSONArray().put(bar("09:31",35)).put(bar("09:32",36))
                .put(bar("09:35",34)).put(bar("11:30",37)).put(bar("12:15",98))
                .put(bar("13:01",33)).put(bar("13:02",32));
        PhoneMarketPlot plot=new PhoneMarketPlot(snapshot(rows),true,true,true);
        float[] slots={0,1,2,5,120,120,121,122};
        boolean[] starts={true,false,false,true,true,true,false,false};
        assertEquals(slots.length,plot.trace.size());
        for(int i=0;i<slots.length;i++) {
            assertEquals(slots[i],plot.trace.get(i).slot,.001f);
            assertEquals(starts[i],plot.trace.get(i).start);
        }
        assertEquals(34.9,plot.trace.get(0).price,.0001);
        assertEquals(32.9,plot.trace.get(5).price,.0001);
        assertEquals(32,plot.lastClose(),0);
    }

    @Test public void trueTickAdvancesLineWithoutUsingFutureCandleEnd() throws Exception {
        JSONObject history=snapshot(new JSONArray().put(bar("14:32",35)));
        JSONObject ticks=new JSONObject().put("symbol","000063.SZ").put("trades",new JSONArray().put(
                new JSONObject().put("source_at",DAY+"T14:32:18+08:00").put("sequence",10).put("price",36)));
        JSONObject data=PhoneRealtimeCandles.apply(history,ticks,SYMBOL,LocalDateTime.parse(DAY+"T14:32:20"));
        PhoneMarketPlot plot=new PhoneMarketPlot(data,true,true,true);
        assertEquals(2,plot.trace.size());
        PhoneMarketPlot.TracePoint recent=plot.trace.get(1);
        assertEquals(212.3f,recent.slot,.0001f);
        assertEquals(36,recent.price,0);
        assertFalse(recent.start);
        // A cold start with just one trade cannot backfill the opening of that minute.
        JSONObject empty=new JSONObject().put("symbol",SYMBOL);
        JSONObject one=PhoneRealtimeCandles.apply(empty,ticks,SYMBOL,LocalDateTime.parse(DAY+"T14:32:20"));
        PhoneMarketPlot.TracePoint only=new PhoneMarketPlot(one,true,true,true).trace.get(0);
        assertTrue(only.start);
        assertEquals(212.3f,only.slot,.001f);
    }

    @Test public void cachedPartialOpeningMinuteDoesNotInventOpeningPriceOrDayExtrema() throws Exception {
        JSONArray rows = new JSONArray().put(bar("09:31",35).put("first_tick_at",DAY+"T09:30:42"));
        JSONObject restored = PhoneRealtimeCandles.history(SYMBOL,"","已缓存",rows,LocalDateTime.parse(DAY+"T09:30:54"));
        restored.put("quote_at",DAY+" 09:30:51");
        assertFalse(restored.has("high") || restored.has("low"));
        PhoneMarketPlot line = new PhoneMarketPlot(restored,true,false,false);
        assertEquals(2,line.trace.size());
        assertEquals(.70f,line.trace.get(0).slot,.0001f);
        assertEquals(.85f,line.trace.get(1).slot,.0001f);
    }

    @Test public void overlaysToggleIndependentlyWithoutMovingEitherPriceScale() throws Exception {
        JSONArray rows=new JSONArray();
        for(int i=0;i<70;i++) {
            String clock=java.time.LocalTime.of(9,31).plusMinutes(i).toString();
            rows.put(bar(clock,i==0?36:35));
        }
        JSONObject trade=new JSONObject().put("bar_time",DAY+" 10:30").put("price",35.1).put("side","B");
        JSONObject book=new JSONObject().put("source_at",DAY+" 10:40:00").put("levels",new JSONArray()
                .put(new JSONObject().put("side","bid").put("volume",123).put("price",35.00))
                .put(new JSONObject().put("side","ask").put("volume",456).put("price",99.0)));
        JSONObject s=snapshot(rows).put("fills",new JSONArray().put(trade)).put("book",book);
        for(boolean line:new boolean[]{true,false}) {
            PhoneMarketPlot base=new PhoneMarketPlot(s,line,false,false);
            assertFalse(base.hasBook()); assertFalse(base.hasSignals());
            for(boolean marks:new boolean[]{true,false}) for(boolean depth:new boolean[]{true,false}) {
                PhoneMarketPlot candidate=new PhoneMarketPlot(s,line,marks,depth);
                assertEquals(marks,candidate.hasSignals());
                assertEquals(depth,candidate.hasBook());
                if (depth) assertEquals("L2 千档 采样 10:40:00",candidate.bookLabel());
                assertEquals(base.priceY(35.0,400,320),candidate.priceY(35.0,400,320),0f);
                assertEquals(base.priceY(35.5,400,320),candidate.priceY(35.5,400,320),0f);
            }
        }
        // An early valid signal belongs on the full-day trace, outside the 60-candle view.
        trade.put("bar_time",DAY+" 09:31").put("price",36);
        assertTrue(new PhoneMarketPlot(s,true,true,true).hasSignals());
        assertFalse(new PhoneMarketPlot(s,false,true,true).hasSignals());
        assertTrue(new PhoneMarketPlot(s,false,true,true,true).hasSignals());
        assertEquals(71,new PhoneMarketPlot(s,true,false,false).trace.size());
        assertTrue(new PhoneMarketPlot(s,false,false,false).trace.isEmpty());
    }

    @Test public void widgetCandlesLeaveMissingMinutesEmptyAndFoldLunchAtTheCenter() throws Exception {
        JSONArray rows = new JSONArray().put(bar("09:31",35)).put(bar("10:01",36)).put(bar("11:30",34))
                .put(bar("13:01",33)).put(bar("14:02",32)).put(bar("15:00",31));
        PhoneMarketPlot plot = new PhoneMarketPlot(snapshot(rows),false,true,true,true);
        float[] centers = {.5f,30.5f,119.5f,120.5f,181.5f,239.5f};
        for (int i=0; i<centers.length; i++) assertEquals(centers[i],plot.barX(i,0,240),.0001f);
        JSONObject partial = snapshot(new JSONArray().put(bar("13:01",33))).put("quote_at",DAY+" 13:00:12");
        assertEquals(120.1f,new PhoneMarketPlot(partial,false,true,true,true).barX(0,0,240),.0001f);
        // The full-size candle view uses the recent-candle layout.
        assertEquals(10f,new PhoneMarketPlot(snapshot(rows),false,true,true).barX(0,0,240),.0001f);
    }

    @Test public void fiveMinuteAverageRestartsAfterGapsAndUsesHistoryBeforeTheVisibleWindow() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i=0; i<75; i++) {
            if (i==31) continue;
            String t = java.time.LocalTime.of(9,31).plusMinutes(i).toString();
            rows.put(bar(t,20+i));
        }
        JSONObject s = snapshot(rows);
        PhoneMarketPlot day = new PhoneMarketPlot(s,false,false,false,true);
        assertTrue(Double.isNaN(day.averageAt(3)));
        assertEquals(22,day.averageAt(4),.000001);
        assertTrue(Double.isNaN(day.averageAt(34)));
        assertEquals(54,day.averageAt(35),.000001);
        PhoneMarketPlot rolling = new PhoneMarketPlot(s,false,false,false);
        assertEquals(32,rolling.averageAt(0),.000001);
    }
}
