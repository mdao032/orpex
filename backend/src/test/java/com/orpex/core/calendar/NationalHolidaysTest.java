package com.orpex.core.calendar;

import static com.orpex.core.calendar.NationalHolidays.Country.FRANCE;
import static com.orpex.core.calendar.NationalHolidays.Country.MALI;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NationalHolidaysTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void easterSundayMatchesKnownDates() {
        assertEquals(d("2024-03-31"), NationalHolidays.easterSunday(2024));
        assertEquals(d("2025-04-20"), NationalHolidays.easterSunday(2025));
        assertEquals(d("2026-04-05"), NationalHolidays.easterSunday(2026));
        assertEquals(d("2027-03-28"), NationalHolidays.easterSunday(2027));
        assertEquals(d("2038-04-25"), NationalHolidays.easterSunday(2038)); // date la plus tardive possible
        assertEquals(d("2285-03-22"), NationalHolidays.easterSunday(2285)); // date la plus précoce possible
    }

    @Test
    void france_elevenHolidaysIn2026InDateOrder() {
        var holidays = NationalHolidays.of(FRANCE, 2026);

        assertEquals(List.of(d("2026-01-01"), d("2026-04-06"), d("2026-05-01"), d("2026-05-08"), d("2026-05-14"),
                d("2026-05-25"), d("2026-07-14"), d("2026-08-15"), d("2026-11-01"), d("2026-11-11"),
                d("2026-12-25")), List.copyOf(holidays.keySet()));
        assertEquals("Lundi de Pâques", holidays.get(d("2026-04-06")));
        assertEquals("Ascension", holidays.get(d("2026-05-14")));
        assertEquals("Lundi de Pentecôte", holidays.get(d("2026-05-25")));
    }

    @Test
    void france_movableHolidaysFollowEaster() {
        var holidays = NationalHolidays.of(FRANCE, 2027);

        assertEquals("Lundi de Pâques", holidays.get(d("2027-03-29")));
        assertEquals("Ascension", holidays.get(d("2027-05-06")));
        assertEquals("Lundi de Pentecôte", holidays.get(d("2027-05-17")));
    }

    @Test
    void mali_eightComputableHolidaysIn2026_islamicHolidaysLeftForManualEntry() {
        var holidays = NationalHolidays.of(MALI, 2026);

        assertEquals(List.of(d("2026-01-01"), d("2026-01-20"), d("2026-03-26"), d("2026-04-06"), d("2026-05-01"),
                d("2026-05-25"), d("2026-09-22"), d("2026-12-25")), List.copyOf(holidays.keySet()));
        assertEquals("Fête de l'Armée", holidays.get(d("2026-01-20")));
        assertEquals("Fête de l'Indépendance", holidays.get(d("2026-09-22")));
        assertEquals(4, MALI.manualDays().size());
        assertEquals(List.of(), FRANCE.manualDays());
    }
}
