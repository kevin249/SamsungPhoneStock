package com.kevin.market;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 交易所日历返回的开市日期白名单；未来未覆盖的日期不能按星期推断。 */
public final class TradingCalendar {
    public enum Day { OPEN, CLOSED, UNKNOWN }
    private final LocalDate first, lastKnown;
    private final Set<LocalDate> open;

    public TradingCalendar(LocalDate start, LocalDate end, Collection<String> dates) {
        if (start.isAfter(end)) throw new IllegalArgumentException("Invalid calendar range");
        Set<LocalDate> parsed=new HashSet<>();
        LocalDate latest=null;
        for (String value:dates) {
            LocalDate date=parseDate(value);
            if (date.isBefore(start) || date.isAfter(end) || !parsed.add(date))
                throw new IllegalArgumentException("Invalid calendar date");
            if (latest==null || date.isAfter(latest)) latest=date;
        }
        first=start;
        lastKnown=latest;
        open=Collections.unmodifiableSet(parsed);
    }

    public static LocalDate parseDate(String date) {
        return LocalDate.parse(date,date.length()==8 ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static TradingCalendar fromTdx(JSONObject payload) throws Exception {
        if (!payload.optBoolean("ok") || !"tdx".equals(payload.optString("source")) || !"SH".equals(payload.optString("market")))
            throw new IOException("Invalid calendar source");
        JSONArray rows=payload.getJSONArray("dates");
        if (rows.length()==0 || rows.length()!=payload.getInt("row_count")) throw new IOException("Incomplete calendar");
        List<String> dates=new ArrayList<>();
        for (int i=0;i<rows.length();i++) dates.add(rows.getString(i));
        return new TradingCalendar(parseDate(payload.getString("start_date")),parseDate(payload.getString("end_date")),dates);
    }

    public Day day(LocalDate date) {
        if (open.contains(date)) return Day.OPEN;
        // TDX 可能只返回已发生的交易日；请求的 end_date 不是已覆盖到的日期。
        if (lastKnown==null || date.isBefore(first) || date.isAfter(lastKnown)) return Day.UNKNOWN;
        return Day.CLOSED;
    }
}
