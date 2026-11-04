package com.kevin.watchmcp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.time.LocalDate;

/** 独立存储检查实际日历格式、持久缓存和错误响应保护，不改设备时间。 */
final class DialCalendarProbe {
    static void run(Context context) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences("dial-calendar-test",Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        try {
            JSONObject data=new JSONObject("{\"ok\":true,\"source\":\"tdx\",\"market\":\"SH\",\"start_date\":\"20260930\",\"end_date\":\"20261130\",\"row_count\":3,\"dates\":[\"20260930\",\"20261008\",\"20261009\"]}");
            DialCalendar.save(prefs,data);
            prefs.edit().commit();
            prefs=context.getSharedPreferences("dial-calendar-test",Context.MODE_PRIVATE);
            if (DialCalendar.day(prefs,LocalDate.of(2026,10,1))!=TradingCalendar.Day.CLOSED
                    || DialCalendar.day(prefs,LocalDate.of(2026,10,8))!=TradingCalendar.Day.OPEN
                    || DialCalendar.day(prefs,LocalDate.of(2026,11,4))!=TradingCalendar.Day.UNKNOWN)
                throw new AssertionError("cached calendar classification");
            String original=prefs.getString(DialCalendar.CACHE,"");
            for (String invalid:new String[]{data.toString().replace("\"row_count\":3","\"row_count\":4"),
                    data.toString().replace("\"ok\":true","\"ok\":false"),
                    data.toString().replace("\"market\":\"SH\"","\"market\":\"HK\"")}) {
                boolean rejected=false;
                try { DialCalendar.save(prefs,new JSONObject(invalid)); } catch (Exception expected) { rejected=true; }
                if (!rejected || !original.equals(prefs.getString(DialCalendar.CACHE,"")))
                    throw new AssertionError("bad response replaced cached calendar");
            }
            prefs.edit().putString(DialCalendar.CACHE,"broken").commit();
            if (DialCalendar.day(prefs,LocalDate.of(2026,10,8))!=TradingCalendar.Day.UNKNOWN)
                throw new AssertionError("corrupt cache enabled one-second updates");
        } finally { prefs.edit().clear().commit(); }
    }
}
