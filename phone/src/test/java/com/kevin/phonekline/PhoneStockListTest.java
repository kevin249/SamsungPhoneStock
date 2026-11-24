package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class PhoneStockListTest {
    @Test public void pagesPreserveOrderAndStyleAcrossScreenSizes() {
        List<String> stocks = new ArrayList<>();
        Set<String> minis = new HashSet<>();
        for (int i = 0; i < 80; i++) {
            String code = "sz" + (100000 + i);
            stocks.add(code);
            if (i % 5 != 0) minis.add(code);
        }
        for (int height : Arrays.asList(90, 184, 218, 360, 940)) {
            List<String> visible = new ArrayList<>();
            List<PhoneStockLayout.Page> pages = PhoneStockLayout.pages(stocks, minis, code -> 332,
                    height, 26, 24, 332, PhoneStockLayout.MIN_FULL_HEIGHT_DP);
            for (PhoneStockLayout.Page page : pages) {
                int used = 0;
                assertFalse(page.stocks.isEmpty());
                assertEquals(page.stocks.size(), page.heights.length);
                for (int i = 0; i < page.heights.length; i++) {
                    if (minis.contains(page.stocks.get(i))) assertEquals(26, page.heights[i]);
                    assertTrue(page.heights[i] > 0);
                    used += page.heights[i];
                }
                assertTrue(used <= height - (pages.size() > 1 ? 24 : 0));
                visible.addAll(page.stocks);
            }
            assertEquals(stocks, visible);
        }
        assertEquals("1d", PhoneDisplayStocks.period(minis, stocks.get(0), "1d"));
        assertEquals("realtime", PhoneDisplayStocks.period(minis, stocks.get(1), "1d"));
    }

    @Test public void fullChartsShareOneScaleWhileMiniKeepsItsOwnHeight() {
        List<String> stocks = Arrays.asList("a", "mini", "b");
        Set<String> minis = java.util.Collections.singleton("mini");
        List<PhoneStockLayout.Page> fitted = PhoneStockLayout.pages(stocks, minis,
                code -> code.equals("a") ? 260 : 156, 346, 26, 24, 332, PhoneStockLayout.MIN_FULL_HEIGHT_DP);
        assertEquals(1, fitted.size());
        assertEquals(stocks, fitted.get(0).stocks);
        assertArrayEquals(new int[]{200, 26, 120}, fitted.get(0).heights);
        List<PhoneStockLayout.Page> odd = PhoneStockLayout.pages(stocks, minis, code -> 332,
                243, 26, 24, 332, PhoneStockLayout.MIN_FULL_HEIGHT_DP);
        assertEquals(1, odd.size());
        int[] pixels = odd.get(0).heights;
        assertEquals(26, pixels[1]);
        assertEquals(217, pixels[0] + pixels[2]);
        assertTrue(Math.abs(pixels[0] - pixels[2]) <= 1);
    }

    @Test public void tenFullChartsFitAtTheCompactLimitAndOverflowPreservesOrder() {
        List<String> stocks = new ArrayList<>();
        for (int i = 0; i < 10; i++) stocks.add("sz" + (200000 + i));
        List<PhoneStockLayout.Page> fit = PhoneStockLayout.pages(stocks, java.util.Collections.emptySet(),
                code -> 332, 320, 26, 24, 332, PhoneStockLayout.MIN_FULL_HEIGHT_DP);
        assertEquals(1, fit.size());
        for (int row : fit.get(0).heights) assertEquals(32, row);
        stocks.add("sz200010");
        List<PhoneStockLayout.Page> overflow = PhoneStockLayout.pages(stocks, java.util.Collections.emptySet(),
                code -> 332, 320, 26, 24, 332, PhoneStockLayout.MIN_FULL_HEIGHT_DP);
        assertEquals(2, overflow.size());
        assertEquals(stocks.subList(0, 9), overflow.get(0).stocks);
        assertEquals(stocks.subList(9, 11), overflow.get(1).stocks);
    }

    @Test public void miniUsesSameIntradayTraceIncludingGapsAndFormingMinute() throws Exception {
        JSONArray bars = new JSONArray();
        for (String time : Arrays.asList("09:31", "09:32", "09:45", "11:30", "13:01", "13:02"))
            bars.put(new JSONObject().put("time", "2026-11-23 " + time)
                    .put("open", 10).put("high", 11).put("low", 10).put("close", 10.5));
        JSONObject quote = new JSONObject().put("bars", bars).put("date", "2026-11-23").put("quote_at", "2026-11-23 13:01:30");
        PhoneMarketPlot full = new PhoneMarketPlot(quote, true, true, true);
        PhoneMarketPlot mini = PhoneMarketPlot.mini(quote);
        assertFalse(mini.trace.isEmpty());
        assertEquals(full.lastClose(), mini.lastClose(), 0);
        assertEquals(full.trace.size(), mini.trace.size());
        for (int i = 0; i < full.trace.size(); i++) {
            assertEquals(full.trace.get(i).start, mini.trace.get(i).start);
            assertEquals(full.trace.get(i).slot, mini.trace.get(i).slot, 0);
            assertEquals(full.trace.get(i).price, mini.trace.get(i).price, 0);
        }
        assertTrue(PhoneMarketPlot.mini(null).trace.isEmpty());
    }
}
