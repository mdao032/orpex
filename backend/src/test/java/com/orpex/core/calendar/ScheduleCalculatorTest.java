package com.orpex.core.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTest {

    private final ScheduleCalculator weekendsOnly = new ScheduleCalculator(BusinessCalendar.weekendsOnly());

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // Exemples de la spécification, section 6 (heure 09:00, jours ouvrés = oui).

    @Test
    void dueOnFriday_threeDaysBefore_givesFourSends() {
        var rule = SchedulingRule.beforeDue(d("2026-10-30"), 3, true);
        assertEquals(List.of(d("2026-10-27"), d("2026-10-28"), d("2026-10-29"), d("2026-10-30")),
                weekendsOnly.sendDates(rule));
    }

    @Test
    void dueOnMonday_threeDaysBefore_skipsTheWeekend() {
        var rule = SchedulingRule.beforeDue(d("2026-11-02"), 3, true);
        assertEquals(List.of(d("2026-10-30"), d("2026-11-02")), weekendsOnly.sendDates(rule));
    }

    @Test
    void dueOnSaturday_lastSendIsMovedToFridayWithoutDuplicate() {
        var rule = SchedulingRule.beforeDue(d("2026-10-31"), 3, true);
        assertEquals(List.of(d("2026-10-28"), d("2026-10-29"), d("2026-10-30")), weekendsOnly.sendDates(rule));
    }

    @Test
    void dueOnSunday_withZeroDaysBefore_isMovedBackToFriday() {
        var rule = SchedulingRule.beforeDue(d("2026-11-01"), 0, true);
        assertEquals(List.of(d("2026-10-30")), weekendsOnly.sendDates(rule));
    }

    @Test
    void dueOnSaturday_withoutBusinessDaysOnly_keepsEveryDay() {
        var rule = SchedulingRule.beforeDue(d("2026-10-31"), 2, false);
        assertEquals(List.of(d("2026-10-29"), d("2026-10-30"), d("2026-10-31")), weekendsOnly.sendDates(rule));
    }

    @Test
    void period_bothBoundsAreIncluded() {
        var rule = SchedulingRule.period(d("2026-10-12"), d("2026-10-14"), false);
        assertEquals(List.of(d("2026-10-12"), d("2026-10-13"), d("2026-10-14")), weekendsOnly.sendDates(rule));
    }

    @Test
    void period_skipsHolidaysWhenBusinessDaysOnly() {
        var calendar = new BusinessCalendar(List.of(d("2026-11-11")));
        var rule = SchedulingRule.period(d("2026-11-09"), d("2026-11-13"), true);
        assertEquals(List.of(d("2026-11-09"), d("2026-11-10"), d("2026-11-12"), d("2026-11-13")),
                new ScheduleCalculator(calendar).sendDates(rule));
    }

    @Test
    void once_onSaturday_withBusinessDaysOnly_isMovedToFriday() {
        assertEquals(List.of(d("2026-10-30")),
                weekendsOnly.sendDates(SchedulingRule.once(d("2026-10-31"), true)));
    }

    @Test
    void once_withoutBusinessDaysOnly_keepsTheGivenDate() {
        assertEquals(List.of(d("2026-10-31")),
                weekendsOnly.sendDates(SchedulingRule.once(d("2026-10-31"), false)));
    }
}
