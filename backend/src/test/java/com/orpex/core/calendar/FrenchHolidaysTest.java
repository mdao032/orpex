package com.orpex.core.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrenchHolidaysTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void easterSundayMatchesKnownDates() {
        assertEquals(d("2024-03-31"), FrenchHolidays.easterSunday(2024));
        assertEquals(d("2025-04-20"), FrenchHolidays.easterSunday(2025));
        assertEquals(d("2026-04-05"), FrenchHolidays.easterSunday(2026));
        assertEquals(d("2027-03-28"), FrenchHolidays.easterSunday(2027));
        assertEquals(d("2038-04-25"), FrenchHolidays.easterSunday(2038)); // date la plus tardive possible
        assertEquals(d("2285-03-22"), FrenchHolidays.easterSunday(2285)); // date la plus précoce possible
    }

    @Test
    void elevenHolidaysIn2026InDateOrder() {
        var holidays = FrenchHolidays.of(2026);

        assertEquals(List.of(d("2026-01-01"), d("2026-04-06"), d("2026-05-01"), d("2026-05-08"), d("2026-05-14"),
                d("2026-05-25"), d("2026-07-14"), d("2026-08-15"), d("2026-11-01"), d("2026-11-11"),
                d("2026-12-25")), List.copyOf(holidays.keySet()));
        assertEquals("Lundi de Pâques", holidays.get(d("2026-04-06")));
        assertEquals("Ascension", holidays.get(d("2026-05-14")));
        assertEquals("Lundi de Pentecôte", holidays.get(d("2026-05-25")));
    }

    @Test
    void movableHolidaysFollowEaster() {
        var holidays = FrenchHolidays.of(2027);

        assertEquals("Lundi de Pâques", holidays.get(d("2027-03-29")));
        assertEquals("Ascension", holidays.get(d("2027-05-06")));
        assertEquals("Lundi de Pentecôte", holidays.get(d("2027-05-17")));
    }
}
