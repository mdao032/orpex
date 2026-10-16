package com.orpex.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.orpex.account.SummaryFrequency;
import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.notification.NotificationSettingsService.Workday;
import com.orpex.notification.SummaryService.Span;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Quand part le résumé, et ce qu'il couvre, selon la fréquence choisie (semaine du lundi 1er avril 2030). */
class SummaryServiceTest {

    private static final Workday WORKDAY = new Workday(LocalTime.of(8, 0), LocalTime.of(18, 0));
    private static final BusinessCalendar WEEKENDS = BusinessCalendar.weekendsOnly();
    private static final LocalDate MONDAY = LocalDate.parse("2030-04-01");
    private static final LocalDate FRIDAY = LocalDate.parse("2030-04-05");

    @Test
    void dailyAtTheEndOfTheWorkdayCoversTodayAndTomorrow() {
        assertThat(span(SummaryFrequency.DAILY, MONDAY, "17:59", WEEKENDS)).isNull();
        assertThat(span(SummaryFrequency.DAILY, MONDAY, "18:00", WEEKENDS))
                .isEqualTo(new Span(MONDAY, MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(1), false));
    }

    @Test
    void weeklyOnFridayCoversTheWeekAndTheNextOne() {
        assertThat(span(SummaryFrequency.WEEKLY_FRIDAY, MONDAY.plusDays(3), "19:00", WEEKENDS)).isNull();
        assertThat(span(SummaryFrequency.WEEKLY_FRIDAY, FRIDAY, "17:00", WEEKENDS)).isNull();
        assertThat(span(SummaryFrequency.WEEKLY_FRIDAY, FRIDAY, "18:00", WEEKENDS)).isEqualTo(new Span(MONDAY, FRIDAY,
                LocalDate.parse("2030-04-08"), LocalDate.parse("2030-04-14"), true));
    }

    @Test
    void weeklyOnFridayMovesToThursdayWhenFridayIsAHoliday() {
        BusinessCalendar fridayOff = new BusinessCalendar(List.of(FRIDAY));
        assertThat(span(SummaryFrequency.WEEKLY_FRIDAY, FRIDAY.minusDays(1), "18:00", fridayOff)).isNotNull();
    }

    @Test
    void weeklyOnMondayAtTheStartOfTheWorkdayCoversLastWeekAndThisWeek() {
        LocalDate next = LocalDate.parse("2030-04-08");
        assertThat(span(SummaryFrequency.WEEKLY_MONDAY, next, "07:59", WEEKENDS)).isNull();
        assertThat(span(SummaryFrequency.WEEKLY_MONDAY, next, "08:00", WEEKENDS)).isEqualTo(new Span(MONDAY,
                LocalDate.parse("2030-04-07"), next, LocalDate.parse("2030-04-14"), true));
        assertThat(span(SummaryFrequency.WEEKLY_MONDAY, next.plusDays(1), "09:00", WEEKENDS)).isNull();
    }

    @Test
    void weeklyOnMondayMovesToTuesdayWhenMondayIsAHoliday() {
        LocalDate next = LocalDate.parse("2030-04-08");
        BusinessCalendar mondayOff = new BusinessCalendar(List.of(next));
        assertThat(span(SummaryFrequency.WEEKLY_MONDAY, next.plusDays(1), "08:00", mondayOff)).isNotNull();
    }

    private static Span span(SummaryFrequency frequency, LocalDate day, String time, BusinessCalendar calendar) {
        return SummaryService.span(frequency, day, LocalTime.parse(time), WORKDAY, calendar);
    }
}
