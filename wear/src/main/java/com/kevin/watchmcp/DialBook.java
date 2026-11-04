package com.kevin.watchmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 按 K 线价格范围汇总千档盘口；远端价位不参与图表缩放。 */
final class DialBook {
    static final int REQUESTED_DEPTH = 1000;
    private static final int BINS = 32;

    static JSONObject profile(JSONArray rows, double low, double high) throws JSONException {
        if (rows == null || rows.length() == 0) return null;
        if (!Double.isFinite(low) || !Double.isFinite(high) || low<=0 || high<low) return null;
        if (high==low) { low-=.01; high+=.01; }
        double[] prices = new double[rows.length()];
        double[] volumes = new double[rows.length()];
        boolean[] bids = new boolean[rows.length()];
        int bidCount = 0, askCount = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            double price = row.optDouble("price"), volume = row.optDouble("volume");
            String side = row.optString("side");
            if (!Double.isFinite(price) || !Double.isFinite(volume) || price <= 0 || volume <= 0
                    || !("bid".equals(side) || "ask".equals(side))) continue;
            prices[i] = price;
            volumes[i] = volume;
            bids[i] = "bid".equals(side);
            if (bids[i]) bidCount++; else askCount++;
        }
        if (bidCount + askCount == 0) return null;
        double[][] sums = new double[2][BINS];
        for (int i = 0; i < prices.length; i++) {
            if (volumes[i] == 0 || prices[i]<low || prices[i]>high) continue;
            int bin = high > low ? (int)Math.round((prices[i] - low) / (high - low) * (BINS - 1)) : 0;
            sums[bids[i] ? 0 : 1][bin] += volumes[i];
        }
        return new JSONObject().put("low", low).put("high", high)
                .put("bid_count", bidCount).put("ask_count", askCount)
                .put("bid", new JSONArray(sums[0])).put("ask", new JSONArray(sums[1]));
    }
}
