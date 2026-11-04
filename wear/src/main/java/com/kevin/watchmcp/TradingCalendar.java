package com.kevin.watchmcp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 交易所日历返回的开市日期白名单；未来未覆盖的日期不能按星期推断。 */
final class TradingCalendar {
    enum Day { OPEN, CLOSED, UNKNOWN }
    private final LocalDate first, lastKnown;
    private final Set<LocalDate> open;

    TradingCalendar(LocalDate start, LocalDate end, Collection<String> dates) {
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

    static LocalDate parseDate(String date) {
        return LocalDate.parse(date,date.length()==8 ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
    }

    Day day(LocalDate date) {
        if (open.contains(date)) return Day.OPEN;
        // TDX 可能只返回已发生的交易日；请求的 end_date 不是已覆盖到的日期。
        if (lastKnown==null || date.isBefore(first) || date.isAfter(lastKnown)) return Day.UNKNOWN;
        return Day.CLOSED;
    }
}
