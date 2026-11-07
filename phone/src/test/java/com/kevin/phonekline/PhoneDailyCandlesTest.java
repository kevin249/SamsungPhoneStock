package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneDailyCandlesTest {
    private JSONObject bar(String day, double close) throws Exception {
        return new JSONObject().put("time",day).put("open",10).put("high",20).put("low",8).put("close",close);
    }

    @Test public void acceptsTdxDailyDateField() throws Exception {
        JSONObject row=bar("2026-11-06",12);
        row.remove("time"); row.put("date","2026-11-06");
        JSONObject daily=PhoneDailyCandles.history("sz000063","",new JSONArray().put(row));
        assertEquals("2026-11-06 15:00",daily.getJSONArray("bars").getJSONObject(0).getString("time"));
    }

    @Test public void keepsDistinctTradingDatesAndRejectsInvalidRows() throws Exception {
        JSONArray rows=new JSONArray().put(bar("2026-10-30",11)).put(bar("2026-10-29",10))
                .put(bar("2026-10-30",12)).put(bar("invalid",15)).put(bar("2026-10-28",100));
        JSONObject daily=PhoneDailyCandles.history("sz000063","中兴通讯",rows);
        assertEquals(2,daily.getJSONArray("bars").length());
        assertEquals(12,daily.getDouble("price"),0);
        PhoneMarketPlot plot=new PhoneMarketPlot(daily,false,true,true);
        assertEquals(12,plot.lastClose(),0);
        assertTrue(plot.barX(1,0,600)>plot.barX(0,0,600));
    }

    @Test public void liveQuoteUpdatesOnlyMatchingDailyBarWithoutMutatingHistory() throws Exception {
        JSONObject daily=PhoneDailyCandles.history("sz000063","中兴通讯",new JSONArray()
                .put(bar("2026-10-29",11)).put(bar("2026-10-30",12)));
        JSONObject live=new JSONObject().put("symbol","sz000063").put("date","2026-10-30")
                .put("price",15).put("high",22).put("low",9).put("quote_at","2026-10-30 14:28:00");
        JSONObject merged=PhoneDailyCandles.merge(daily,live);
        assertEquals(15,merged.getDouble("price"),0);
        assertEquals(22,merged.getJSONArray("bars").getJSONObject(1).getDouble("high"),0);
        assertEquals(8,merged.getJSONArray("bars").getJSONObject(1).getDouble("low"),0);
        assertEquals(12,daily.getJSONArray("bars").getJSONObject(1).getDouble("close"),0);
        live.put("symbol","sh603986");
        assertSame(daily,PhoneDailyCandles.merge(daily,live));
    }

    @Test public void newDayRequiresOpeningDataAndPreservesPreviousDays() throws Exception {
        JSONObject daily=PhoneDailyCandles.history("sz000063","",new JSONArray().put(bar("2026-10-29",12)));
        JSONObject minute=bar("2026-10-30 14:00",15);
        JSONObject live=new JSONObject().put("symbol","sz000063").put("date","2026-10-30")
                .put("price",15).put("high",20).put("low",8).put("bars",new JSONArray().put(minute));
        assertEquals(1,PhoneDailyCandles.merge(daily,live).getJSONArray("bars").length());
        minute.put("time","2026-10-30 09:31");
        JSONObject merged=PhoneDailyCandles.merge(daily,live);
        assertEquals(2,merged.getJSONArray("bars").length());
        assertEquals(12,merged.getDouble("previous"),0);
        assertEquals(10,merged.getJSONArray("bars").getJSONObject(1).getDouble("open"),0);
    }

    @Test public void dailyIndicatorsSpanWeekendsAndKeepSixtyVisibleBars() throws Exception {
        JSONArray rows=new JSONArray();
        java.time.LocalDate date=java.time.LocalDate.of(2026,5,1);
        for(int i=0;i<85;i++) rows.put(bar(date.plusDays(i*2L).toString(),10+i*.05));
        JSONObject daily=PhoneDailyCandles.history("sz000063","",rows);
        PhoneMarketPlot plot=new PhoneMarketPlot(daily,false,false,false);
        assertEquals(14.2,plot.lastClose(),1e-6);
        assertTrue(Double.isFinite(plot.tdwAt(59)));
        assertTrue(plot.hasAverage());
        assertEquals(600*(59.5f)/60,plot.barX(59,0,600),.001);
    }
}
