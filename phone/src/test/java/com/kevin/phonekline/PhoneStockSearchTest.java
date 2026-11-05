package com.kevin.phonekline;

import com.kevin.market.StockSearch;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public final class PhoneStockSearchTest {
    @Test public void onlyCompleteCodesCanBeConfirmedWithoutSelectingACandidate() {
        for (String term : new String[] {"", "zxtx", "zhongxing", "中兴", "00006", "中兴 000063"})
            assertNull(StockSearch.normalize(term));
        assertEquals("sz000063",StockSearch.normalize("000063"));
        assertEquals("sh601318",StockSearch.normalize("601318.SH"));
        assertEquals("bj920001",StockSearch.normalize("BJ920001"));
    }

    @Test public void candidatesKeepDistinctSymbolsAndExcludeNonAStocks() throws Exception {
        String response="v_hint="+JSONObject.quote("sz~000063~中兴通讯~zxtx~GP-A"
                +"^sz~000063~重名~zxtx~GP-A^sz~000715~中兴商业~zxsy~GP-A"
                +"^sh~000001~上证指数~szzs~ZS^hk~00700~腾讯控股~txkg~GP")
                +";";
        StockSearch service=fixture(response,"");
        List<StockSearch.Stock> found=service.search("zhongxing");
        assertEquals(2,found.size());
        assertEquals("中兴通讯",found.get(0).name);
        assertEquals("sz000715",found.get(1).symbol);
        assertEquals(1,service.search("000715.SZ").size());
        assertEquals("sz000715",service.search("000715.SZ").get(0).symbol);
        assertTrue(StockSearch.parseTencent("v_hint=\"\"").isEmpty());
    }

    @Test public void emptyFirstFeedFallsBackAndUnmatchedMarketRemainsEmpty() throws Exception {
        JSONArray rows=new JSONArray().put(new JSONObject().put("MktNum","0").put("Classify","AStock")
                .put("Code","920001").put("Name","测试股票"));
        String fallback=new JSONObject().put("QuotationCodeTable",new JSONObject().put("Status",0)
                .put("Data",rows)).toString();
        StockSearch service=fixture("v_hint=\"\"",fallback);
        assertEquals("bj920001",service.search("920001.BJ").get(0).symbol);
        assertTrue(service.search("920001.SH").isEmpty());
    }

    private static StockSearch fixture(String tencent, String eastmoney) {
        return new StockSearch() {
            @Override protected HttpURLConnection open(URL url) {
                String data=url.getHost().endsWith("gtimg.cn")?tencent:eastmoney;
                return new HttpURLConnection(url) {
                    @Override public void connect() {}
                    @Override public void disconnect() {}
                    @Override public boolean usingProxy() {return false;}
                    @Override public int getResponseCode() {return HTTP_OK;}
                    @Override public InputStream getInputStream() {
                        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
                    }
                };
            }
        };
    }
}
