package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.Assert.*;

public final class PhoneSessionAverageTest {
    private static final String DAY = "2026-11-05 ", SYMBOL = "sh603986";
    private static final LocalDateTime CLOSE = LocalDateTime.parse("2026-11-05T16:00:00");

    private JSONObject bar(String minute, double price) throws Exception {
        return new JSONObject().put("time", DAY + minute).put("open", price).put("high", price)
                .put("low", price).put("close", price);
    }

    private JSONObject tdxBar(String minute, double shares, double price) throws Exception {
        return bar(minute, price).put("volume", shares).put("amount", price * shares);
    }

    @Test public void shareWeightedSinceOpenStopsAtMissingTurnoverAndResetsOnNewDay() throws Exception {
        JSONArray raw = new JSONArray().put(tdxBar("09:31",1000,35)).put(tdxBar("09:32",3000,37))
                .put(tdxBar("09:33",0,37)).put(bar("09:34",37)).put(tdxBar("09:35",100,38));
        JSONObject history = PhoneMarketClient.tdxHistory(SYMBOL,raw,CLOSE);
        Map<String,Double> averages = PhoneSessionAverage.prices(history);
        assertEquals(3,averages.size());
        assertEquals(35,averages.get(DAY+"09:31"),0);
        assertEquals(36.5,averages.get(DAY+"09:32"),0);
        assertEquals(36.5,averages.get(DAY+"09:33"),0);
        assertTrue(PhoneSessionAverage.prices(PhoneMarketClient.tdxHistory(SYMBOL,
                new JSONArray().put(tdxBar("10:31",1000,35)), CLOSE)).isEmpty());
        JSONObject nextDay = new JSONObject(history.toString()).put("date","2026-11-06");
        assertTrue(PhoneSessionAverage.prices(nextDay).isEmpty());
    }

    @Test public void formingBarAndIncompleteTradeWindowCannotMoveTheAverage() throws Exception {
        JSONArray raw = new JSONArray().put(tdxBar("09:31",1000,35)).put(tdxBar("09:32",3000,37));
        LocalDateTime at = LocalDateTime.parse("2026-11-05T09:31:21");
        JSONObject chart = PhoneMarketClient.tdxHistory(SYMBOL, raw, at);
        JSONObject trade = new JSONObject().put("price",37.6).put("volume",2000)
                .put("source_at","2026-11-05 09:31:20").put("sequence",23);
        JSONObject tick = new JSONObject().put("trades",new JSONArray().put(trade)).put("symbol",SYMBOL);
        JSONObject updated = PhoneRealtimeCandles.apply(chart,tick,SYMBOL,at);
        assertNotNull(updated);
        assertEquals(1,PhoneSessionAverage.prices(updated).size());
        assertFalse(PhoneSessionAverage.prices(updated).containsKey(DAY+"09:32"));
        JSONObject complete = PhoneRealtimeCandles.mergeHistory(updated,PhoneMarketClient.tdxHistory(SYMBOL,raw,CLOSE));
        assertEquals(36.5,PhoneSessionAverage.prices(complete).get(DAY+"09:32"),0);
        JSONObject late = PhoneRealtimeCandles.mergeHistory(complete,chart);
        assertEquals(36.5,PhoneSessionAverage.prices(late).get(DAY+"09:32"),0);
    }

    @Test public void tencentUsesIndependentDatedCumulativeTotalsAcrossGapsAndLunch() throws Exception {
        JSONArray bars = new JSONArray().put(bar("09:30",35)).put(bar("09:31",36))
                .put(bar("09:32",36)).put(bar("13:01",37)).put(bar("15:01",37));
        JSONArray series = new JSONArray().put("0930 35 10 35000").put("0931 36 40 143000")
                .put("0932 36 - -").put("1301 37 100 365000").put("1501 37 105 383500");
        PhoneMarketClient.attachTencentAverages(bars, new JSONObject().put("date","20261104").put("data",series));
        assertFalse(bars.getJSONObject(0).has(PhoneSessionAverage.AVERAGE));
        PhoneMarketClient.attachTencentAverages(bars, new JSONObject().put("date","20261105").put("data",series));
        JSONObject tencent = PhoneRealtimeCandles.history(SYMBOL,"","腾讯",bars,CLOSE);
        Map<String,Double> prices = PhoneSessionAverage.prices(tencent);
        assertEquals(3,prices.size());
        assertEquals(35,prices.get(DAY+"09:30"),0);
        assertEquals(35.75,prices.get(DAY+"09:31"),0);
        assertEquals(36.50,prices.get(DAY+"13:01"),0);
        // A different source's 09:31 bar already includes the opening auction.
        JSONObject tdx = PhoneMarketClient.tdxHistory(SYMBOL,new JSONArray().put(tdxBar("09:31",4000,35.75)),CLOSE);
        assertEquals(35.75,PhoneSessionAverage.prices(PhoneRealtimeCandles.mergeHistory(tencent,tdx)).get(DAY+"09:31"),0);
    }
}
