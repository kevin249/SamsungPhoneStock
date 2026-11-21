package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneSignalHistoryTest {
    @Test public void confirmedOnlyAndScopedToStockAndDate() throws Exception {
        JSONObject data = new JSONObject("{symbol:'603986.SH',trade_date:'2026-11-09',available:true,markers:["
                + "{time:'14:22',signal:'B1',l2_confirmed:false,price:239.39},"
                + "{time:'14:22',signal:'B',l2_confirmed:true,price:239.39}]}");
        JSONArray marks=PhoneSignalHistory.read(data,"sh603986","2026-11-09");
        assertEquals(1,marks.length());
        assertEquals("B",marks.getJSONObject(0).getString("side"));
        assertEquals(0,PhoneSignalHistory.read(data,"sz000063","2026-11-09").length());
        assertEquals(0,PhoneSignalHistory.read(data,"sh603986","2026-11-06").length());
        data.put("stale_version",true);
        assertEquals(0,PhoneSignalHistory.read(data,"sh603986","2026-11-09").length());
    }

    @Test public void emptyPollAndErrorRetainMarkersButNewDateClearsThem() throws Exception {
        JSONArray marks=new JSONArray("[{bar_time:'2026-11-09 14:22',side:'B',price:239.39}]");
        assertEquals(1,PhoneSignalHistory.merge("2026-11-09",marks,new JSONArray()).length());
        assertEquals(1,PhoneSignalHistory.merge("2026-11-09",marks,null).length());
        assertEquals(1,PhoneSignalHistory.merge("2026-11-09",marks,marks).length());
        assertEquals(0,PhoneSignalHistory.merge("2026-11-10",marks,null).length());
    }
    @Test public void persistentHistoryKeepsOlderDatesAndEmptyUpdatesCannotClearIt() throws Exception {
        JSONArray prior = new JSONArray("[{bar_time:'2026-11-06 14:22',side:'B',price:239.39}]");
        JSONArray current = new JSONArray("[{bar_time:'2026-11-09 10:31',side:'S',price:245.0}]");
        JSONArray all = PhoneSignalHistory.retain(prior,current);
        assertEquals(2,all.length());
        assertEquals(all.toString(),PhoneSignalHistory.retain(all,new JSONArray()).toString());
        assertEquals(all.toString(),PhoneSignalHistory.retain(all,null).toString());
        assertEquals(2,PhoneSignalHistory.retain(all,all).length());
        assertEquals(1,PhoneSignalHistory.merge("2026-11-09",null,all).length());
        assertEquals(0,PhoneSignalHistory.merge("2026-11-10",null,all).length());
        assertEquals(2,PhoneSignalHistory.retain(all,new JSONArray("[{bar_time:'garbage',side:'B',price:1}]")).length());
    }

}
