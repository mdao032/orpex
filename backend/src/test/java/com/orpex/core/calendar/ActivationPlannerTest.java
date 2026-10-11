package com.orpex.core.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivationPlannerTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime NINE = LocalTime.of(9, 0);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 12, hour, minute, 0, 0, PARIS).toInstant();
    }

    private final List<LocalDate> dates = List.of(d("2026-10-09"), d("2026-10-12"), d("2026-10-13"));

    @Test
    void beforeTheSendTime_todayIsStillPlanned_andThePastIsDropped() {
        var split = ActivationPlanner.split(dates, NINE, PARIS, at(8, 0));
        assertEquals(List.of(d("2026-10-12"), d("2026-10-13")), split.planned());
        assertEquals(List.of(), split.missed());
    }

    @Test
    void afterTheSendTime_todayIsMissed_notCaughtUp() {
        var split = ActivationPlanner.split(dates, NINE, PARIS, at(10, 0));
        assertEquals(List.of(d("2026-10-13")), split.planned());
        assertEquals(List.of(d("2026-10-12")), split.missed());
    }
}
