package com.kevin.watchmcp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** 真实 Android JSON/字符集与搜索接口验证，不写入用户自选或连接设置。 */
final class StockSearchProbe {
    static void run() throws Exception {
        String hint = "v_hint=" + JSONObject.quote("sh~600519~贵州茅台~gzmt~GP-A^sh~600519~重复~gzmt~GP-A"
                + "^sh~000001~上证指数~szzs~ZS^hk~00700~腾讯控股~txkg~GP^sz~159915~基金~jj~ETF"
                + "^bj~920001~北交股票~bjgp~GP-A-BJ") + ";";
        List<StockSearchClient.Stock> rows = StockSearchClient.parseTencent(hint);
        if (rows.size() != 2 || !rows.get(0).name.equals("贵州茅台") || !rows.get(1).symbol.equals("bj920001")) {
            throw new AssertionError("Tencent decoding/filtering/deduplication");
        }
        boolean rejected = false;
        try { StockSearchClient.parseTencent("alert('not data')"); }
        catch (Exception expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Invalid script accepted");
        if (!StockSearchClient.parseTencent("v_hint=\"\"").isEmpty()) throw new AssertionError("Empty hint");
        JSONArray data = new JSONArray()
                .put(row("600519", "贵州茅台", "1", "AStock"))
                .put(row("600519", "重复", "1", "AStock"))
                .put(row("000001", "上证指数", "1", "Index"))
                .put(row("013864", "场外基金", "150", "OTCFUND"))
                .put(row("920001", "北交股票", "0", "AStock"));
        rows = StockSearchClient.parseEastmoney(new JSONObject().put("QuotationCodeTable",
                new JSONObject().put("Status", 0).put("Data", data)).toString());
        if (rows.size() != 2 || !rows.get(1).symbol.equals("bj920001")) throw new AssertionError("Eastmoney filtering");
        StockSearchClient client = new StockSearchClient();
        for (String query : new String[]{"600519", "贵州茅台", "guizhoumaotai", "maotai", "GZMT", "600519.SH"}) {
            rows = client.search(query);
            if (rows.isEmpty() || !rows.get(0).symbol.equals("sh600519") || !rows.get(0).name.equals("贵州茅台")) {
                throw new AssertionError("Search failed: " + query);
            }
        }
    }

    private static JSONObject row(String code, String name, String market, String kind) throws Exception {
        return new JSONObject().put("Code", code).put("Name", name).put("MktNum", market).put("Classify", kind);
    }
}
