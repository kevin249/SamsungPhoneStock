package com.kevin.watchmcp;

import com.kevin.market.StockSearch;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reuse market search on the watch's own network policy. */
final class StockSearchClient extends StockSearch {
    @Override protected HttpURLConnection open(URL url) throws IOException {
        return WifiNetworkPolicy.open(url);
    }
}
