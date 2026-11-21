package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDateTime;
import static org.junit.Assert.*;

public class PhoneDepthContinuityTest {
    private final LocalDateTime now = LocalDateTime.parse("2026-11-17T10:30:00");

    private JSONObject response(String symbol, double price) throws Exception {
        return new JSONObject().put("symbol",symbol).put("available",true).put("requested_depth",1000)
                .put("distribution_ready",true).put("source_at",now.minusSeconds(2).toString())
                .put("levels",new JSONArray().put(new JSONObject().put("side","bid").put("price",price).put("volume",100)));
    }

    @Test public void twoStocksRetainTheirOwnVerifiedBooksThroughRejectedResponses() throws Exception {
        JSONObject a = PhoneRealtimeCandles.book(response("000063.SZ",35),"sz000063",now);
        JSONObject b = PhoneRealtimeCandles.book(response("603986.SH",200),"sh603986",now);
        Exception unavailable = assertThrows(PhoneRealtimeCandles.DepthUnavailable.class, () ->
                PhoneRealtimeCandles.book(response("000063.SZ",35).put("available",false),"sz000063",now));
        Exception wrongStock = assertThrows(PhoneRealtimeCandles.DepthUnavailable.class, () ->
                PhoneRealtimeCandles.book(response("000063.SZ",35),"sh603986",now));
        assertSame(a,PhoneRealtimeCandles.retainBook(a,null,unavailable,now));
        assertSame(b,PhoneRealtimeCandles.retainBook(b,null,wrongStock,now));
        assertEquals(35,a.getJSONArray("levels").getJSONObject(0).getDouble("price"),0);
        assertEquals(200,b.getJSONArray("levels").getJSONObject(0).getDouble("price"),0);
        assertNull(PhoneRealtimeCandles.retainBook(null,null,unavailable,now));
    }

    @Test public void failuresDoNotExtendFreshnessAndRevokedAccessClearsImmediately() throws Exception {
        JSONObject book = PhoneRealtimeCandles.book(response("000063.SZ",35),"sz000063",now);
        Exception timeout = new java.net.SocketTimeoutException();
        assertSame(book,PhoneRealtimeCandles.retainBook(book,null,timeout,now.plusSeconds(28)));
        assertNull(PhoneRealtimeCandles.retainBook(book,null,timeout,now.plusSeconds(29)));
        assertNull(PhoneRealtimeCandles.retainBook(book,null,null,now.plusSeconds(29)));
        assertNull(PhoneRealtimeCandles.retainBook(book,null,new PhoneMcpClient.RpcError(403),now));
        assertNull(PhoneRealtimeCandles.retainBook(book,null,new PhoneMcpClient.RpcError(401),now));
    }

    @Test public void recoveryReplacesSnapshotButOlderSamplesCannotRollItBack() throws Exception {
        JSONObject book = PhoneRealtimeCandles.book(response("000063.SZ",35),"sz000063",now);
        JSONObject next = PhoneRealtimeCandles.book(response("000063.SZ",36).put("source_at",now.toString()),"sz000063",now);
        assertSame(next,PhoneRealtimeCandles.retainBook(book,next,null,now));
        assertSame(next,PhoneRealtimeCandles.retainBook(next,book,null,now));
        Exception invalid = new PhoneRealtimeCandles.DepthUnavailable("千档分布未核验");
        assertSame(book,PhoneRealtimeCandles.retainBook(book,next,invalid,now));
        assertFalse(PhoneRealtimeCandles.bookFitsChart(next,new JSONObject()
                .put("date","2026-11-16").put("quote_at","2026-11-16 15:00:00"),now));
    }
}
