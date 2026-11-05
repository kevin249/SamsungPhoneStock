package com.kevin.phonekline;

import static org.junit.Assert.*;
import java.time.LocalDateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PhoneRealtimeCandlesTest {
    private final String symbol="sz000063", day="2026-11-05";
    private final LocalDateTime now=LocalDateTime.parse("2026-11-05T10:32:15");
    private JSONObject row(String stamp,double close) throws Exception {
        return new JSONObject().put("time",stamp).put("open",close).put("high",close+.1).put("low",close-.1).put("close",close);
    }
    private JSONObject chart(JSONArray rows) throws Exception {
        return PhoneRealtimeCandles.history(symbol,"中兴通讯","分钟行情",rows,now);
    }
    private JSONObject trade(String stamp,double price,int sequence) throws Exception {
        return new JSONObject().put("source_at",stamp).put("price",price).put("sequence",sequence).put("side","buy");
    }
    private JSONObject trades(JSONArray trades) throws Exception {
        return new JSONObject().put("symbol","000063.SZ").put("available",true).put("trades",trades);
    }

    @Test public void wholeSessionExtremaIncludeOpeningBarOutsideSixty() throws Exception {
        JSONArray rows=new JSONArray().put(row("2026-11-04 15:00",95));
        for(int i=0;i<61;i++){
            rows.put(row(PhoneRealtimeCandles.BAR.format(now.toLocalDate().atTime(9,31).plusMinutes(i)),i==0?50:35));
        }
        JSONObject data=chart(rows);
        assertEquals(61,data.getJSONArray("bars").length());
        assertEquals(50.1,data.getDouble("high"),.0001);
        assertEquals(34.9,data.getDouble("low"),.0001);
        assertFalse(data.has("previous"));
        assertFalse(chart(new JSONArray().put(row(day+" 10:30",30))).has("high"));
        assertFalse(chart(new JSONArray().put(row(day+" 09:31",30)).put(row(day+" 10:30",31))).has("high"));
    }

    @Test public void newsortedTicksSurviveLateHistoryAndSnapshotsStayImmutable() throws Exception {
        JSONObject first=chart(new JSONArray().put(row(day+" 10:32",35)));
        JSONObject ticks=trades(new JSONArray().put(trade(day+"T10:32:13+08:00",37,3))
                .put(trade(day+"T10:32:12+08:00",36,2)).put(trade(day+"T10:32:13+08:00",35,4)));
        JSONObject updated=PhoneRealtimeCandles.apply(first,ticks,symbol,now);
        assertEquals(1,first.getJSONArray("bars").length());
        JSONObject current=updated.getJSONArray("bars").getJSONObject(1);
        assertEquals(day+" 10:33",current.getString("time"));
        assertEquals(36,current.getDouble("open"),0); assertEquals(37,current.getDouble("high"),0);
        assertEquals(35,current.getDouble("close"),0);
        assertFalse(updated.has("fills"));
        assertNull(PhoneRealtimeCandles.apply(updated,ticks,symbol,now));
        JSONObject late=chart(new JSONArray().put(row(day+" 10:33",34)));
        JSONObject combined=PhoneRealtimeCandles.mergeHistory(updated,late);
        assertEquals(35,combined.getDouble("price"),0);
        assertEquals(35,combined.getJSONArray("bars").getJSONObject(1).getDouble("close"),0);
        assertEquals(33.9,combined.getJSONArray("bars").getJSONObject(1).getDouble("low"),.00001);
        assertEquals(35,current.getDouble("low"),0);
    }

    @Test public void staleWrongSymbolAndSessionTransitions() throws Exception {
        JSONObject data=chart(new JSONArray().put(row("2026-11-04 15:00",33)));
        data.put("book",new JSONObject()).put("fills",new JSONArray()).put("previous",33);
        JSONObject valid=trades(new JSONArray().put(trade(day+" 10:32:14",35,1)));
        assertNull(PhoneRealtimeCandles.apply(data,valid,"sh600519",now));
        assertNull(PhoneRealtimeCandles.apply(data,trades(new JSONArray().put(trade(day+" 10:22:12",35,1))),symbol,now));
        JSONObject today=PhoneRealtimeCandles.apply(data,valid,symbol,now);
        assertEquals(day,today.getString("date"));
        assertFalse(today.has("book") || today.has("fills") || today.has("previous"));
        assertSame(today,PhoneRealtimeCandles.mergeHistory(today,data));
        assertEquals("11:30",PhoneRealtimeCandles.barEnd(LocalDateTime.parse(day+"T11:30:00")).toLocalTime().toString());
        assertEquals("13:01",PhoneRealtimeCandles.barEnd(LocalDateTime.parse(day+"T13:00:00")).toLocalTime().toString());
    }

    @Test public void requestedThousandRequiresVerifiedDatedDepthAndLimitsEachSide() throws Exception {
        JSONArray rows=new JSONArray();
        for(int i=0;i<1004;i++) {
            rows.put(new JSONObject().put("side","bid").put("price",200-i*.01).put("volume",3));
            rows.put(new JSONObject().put("side","ask").put("price",201+i*.01).put("volume",7));
        }
        rows.put(new JSONObject().put("side","bid").put("price",200).put("volume",4));
        JSONObject book=new JSONObject().put("symbol","000063.SZ").put("available",true).put("requested_depth",1000)
                .put("distribution_ready",true).put("source_at",day+"T10:32:10+08:00").put("levels",rows);
        JSONArray levels=PhoneRealtimeCandles.book(book,symbol,now).getJSONArray("levels");
        assertEquals(2000,levels.length());
        assertEquals(200,levels.getJSONObject(0).getDouble("price"),0);
        assertEquals(7,levels.getJSONObject(0).getDouble("volume"),0);
        assertEquals(201,levels.getJSONObject(1000).getDouble("price"),0);
        JSONObject closing=new JSONObject().put("date",day).put("quote_at",day+" 15:00:00");
        assertFalse(PhoneRealtimeCandles.bookFitsChart(book,closing,now.withHour(19)));
        assertTrue(PhoneRealtimeCandles.bookFitsChart(new JSONObject().put("source_at",day+" 15:00:00"),closing,now.withHour(19)));
        assertEquals("盘口已过期", assertThrows(PhoneRealtimeCandles.DepthUnavailable.class,
                    ()->PhoneRealtimeCandles.book(book,symbol,now.plusMinutes(1))).getMessage());
        assertEquals("千档数据源不可用", assertThrows(PhoneRealtimeCandles.DepthUnavailable.class,
                    ()->PhoneRealtimeCandles.book(book.put("available",false),symbol,now)).getMessage());
        assertThrows(PhoneRealtimeCandles.DepthUnavailable.class,
                    ()->PhoneRealtimeCandles.book(book.put("available",true).put("requested_depth",5),symbol,now));
        assertThrows(PhoneRealtimeCandles.DepthUnavailable.class,
                    ()->PhoneRealtimeCandles.book(book.put("requested_depth",1000).put("verified",false),symbol,now));
    }

    @Test public void acceptsSameDayAfterCloseAcquisitionForVerified0559Distribution() throws Exception {
        LocalDateTime afterClose = LocalDateTime.parse(day+"T21:40:12");
        JSONObject response = new JSONObject().put("symbol","000063.SZ").put("available",true).put("requested_depth",1000)
                .put("verified",true).put("distribution_ready",true).put("service_ready",true)
                .put("full_depth_verified",false).put("depth_limit_reached",true)
                .put("source_time_kind","client_received").put("source_at",day+"T21:40:10+08:00")
                .put("levels",new JSONArray().put(new JSONObject().put("side","bid").put("price",33.12).put("volume",7)));
        PhoneRealtimeCandles.BookCache cache=new PhoneRealtimeCandles.BookCache();
        JSONObject normalized=PhoneRealtimeCandles.book(response,symbol,afterClose,cache);
        assertSame(normalized,PhoneRealtimeCandles.book(new JSONObject(response.toString()),symbol,afterClose,cache));
        response.put("source_at",day+"T21:40:11+08:00");
        JSONObject next=PhoneRealtimeCandles.book(new JSONObject(response.toString()),symbol,afterClose,cache);
        assertNotSame(normalized,next);
        assertSame(normalized.getJSONArray("levels"),next.getJSONArray("levels"));
        response.getJSONArray("levels").getJSONObject(0).put("volume",13);
        JSONObject changed=PhoneRealtimeCandles.book(response,symbol,afterClose,cache);
        assertEquals(13,changed.getJSONArray("levels").getJSONObject(0).getDouble("volume"),0);
        assertEquals("client_received",normalized.getString("source_time_kind"));
        JSONObject chart=new JSONObject().put("date",day).put("quote_at",day+" 15:00:00");
        assertTrue(PhoneRealtimeCandles.bookFitsChart(normalized,chart,afterClose));
        LocalDateTime overnight=LocalDateTime.parse("2026-11-06T00:04:20");
        JSONObject midnight=PhoneRealtimeCandles.copy(normalized).put("source_at",overnight.toString());
        assertTrue(PhoneRealtimeCandles.bookFitsChart(midnight,chart,overnight));
        assertFalse(PhoneRealtimeCandles.bookFitsChart(midnight,chart,overnight.withHour(9).withMinute(15)));
        midnight.put("source_time_kind","exchange");
        assertFalse(PhoneRealtimeCandles.bookFitsChart(midnight,chart,overnight));
        assertFalse(PhoneRealtimeCandles.bookFitsChart(normalized,chart.put("date","2026-11-04"),afterClose));
    }

    @Test public void onlyDatedTradingBarsConfirmAnOpenMarket() throws Exception {
        JSONObject bar = new JSONObject().put("time",day+" 10:33").put("close",33.0);
        JSONObject snapshot = new JSONObject().put("symbol",symbol).put("date",day)
                .put("quote_at",day+" 10:32:09").put("bars",new JSONArray().put(bar));
        assertTrue(PhoneLiveTradingDay.hasCurrentBar(snapshot.toString(),symbol,now));
        assertFalse(PhoneLiveTradingDay.hasCurrentBar(snapshot.toString(),"sh603986",now));
        assertFalse(PhoneLiveTradingDay.hasCurrentBar(snapshot.toString(),symbol,now.plusDays(1)));
        assertFalse(PhoneLiveTradingDay.hasCurrentBar(snapshot.toString(),symbol,now.minusMinutes(2)));
        snapshot.put("quote_at",day+" 09:29:00");
        assertFalse(PhoneLiveTradingDay.hasCurrentBar(snapshot.toString(),symbol,now));
    }

    @Test public void onlyPrivateStrategySignalMatchesSymbolDayAndMinute() throws Exception {
        JSONObject payload=new JSONObject().put("direction","sell_first").put("code","000063").put("price",35.8)
                .put("bar_at","2026-11-05T02:30:00Z");
        JSONObject event=new JSONObject().put("event_type","trading.holding_t_signal").put("event_id","123").put("payload",payload);
        JSONObject notifications=new JSONObject().put("notifications",new JSONArray().put(event).put(event).put(trade(day+" 10:29:00",36,2)));
        JSONArray fills=PhoneRealtimeCandles.signals(notifications,symbol,day);
        assertEquals(1,fills.length());
        assertEquals("S",fills.getJSONObject(0).getString("side"));
        assertEquals(day+" 10:30",fills.getJSONObject(0).getString("bar_time"));
        assertEquals(0,PhoneRealtimeCandles.signals(notifications,"sh600519",day).length());
        assertEquals(0,PhoneRealtimeCandles.signals(notifications,symbol,"2026-11-04").length());
    }
}
