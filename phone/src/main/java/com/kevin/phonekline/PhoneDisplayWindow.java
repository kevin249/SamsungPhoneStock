package com.kevin.phonekline;

import android.content.SharedPreferences;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Two shared display parameters, independent of power source; Shanghai hours include lunch. */
final class PhoneDisplayWindow {
    static final String AUTOMATIC = "auto_chart_switch", MODE = "chart_mode";
    private static final LocalTime START = LocalTime.of(9,15), END = LocalTime.of(15,0);
    final boolean automatic;
    final String manualMode;

    PhoneDisplayWindow(boolean automatic, String mode) {
        this.automatic = automatic;
        manualMode = "realtime".equals(mode) || "1d".equals(mode) ? mode : "1m";
    }

    static PhoneDisplayWindow read(SharedPreferences prefs) {
        return new PhoneDisplayWindow(prefs.getBoolean(AUTOMATIC,true),prefs.getString(MODE,"realtime"));
    }

    private static boolean weekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    boolean includes(LocalDateTime now) {
        LocalTime time = now.toLocalTime();
        return weekday(now.toLocalDate()) && !time.isBefore(START) && time.isBefore(END);
    }

    String period(LocalDateTime now, boolean closed) {
        if (!automatic) return manualMode;
        return !closed && includes(now) ? "realtime" : "1d";
    }

    LocalDateTime nextStart(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        if (weekday(date) && now.toLocalTime().isBefore(START)) return date.atTime(START);
        do { date = date.plusDays(1); } while (!weekday(date));
        return date.atTime(START);
    }

    LocalDateTime nextTransition(LocalDateTime now, boolean closed) {
        if (!closed && weekday(now.toLocalDate())) {
            if (now.toLocalTime().isBefore(START)) return now.toLocalDate().atTime(START);
            if (now.toLocalTime().isBefore(END)) return now.toLocalDate().atTime(END);
        }
        return nextStart(now.toLocalDate().atTime(END));
    }
}
