package com.orpex.core.calendar;

import java.time.LocalDate;
import java.time.Month;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les 11 jours fériés légaux de France métropolitaine (Code du travail, art. L3133-1), hors Alsace-Moselle
 * (Vendredi saint et Saint-Étienne en plus). Proposés à l'administrateur, qui reste libre de les modifier.
 */
public final class FrenchHolidays {

    private FrenchHolidays() {
    }

    /** Jours fériés de l'année, par date croissante, avec leur libellé. */
    public static Map<LocalDate, String> of(int year) {
        LocalDate easter = easterSunday(year);
        Map<LocalDate, String> days = new LinkedHashMap<>();
        days.put(LocalDate.of(year, Month.JANUARY, 1), "Jour de l'an");
        days.put(easter.plusDays(1), "Lundi de Pâques");
        days.put(LocalDate.of(year, Month.MAY, 1), "Fête du Travail");
        days.put(LocalDate.of(year, Month.MAY, 8), "Victoire 1945");
        days.put(easter.plusDays(39), "Ascension");
        days.put(easter.plusDays(50), "Lundi de Pentecôte");
        days.put(LocalDate.of(year, Month.JULY, 14), "Fête nationale");
        days.put(LocalDate.of(year, Month.AUGUST, 15), "Assomption");
        days.put(LocalDate.of(year, Month.NOVEMBER, 1), "Toussaint");
        days.put(LocalDate.of(year, Month.NOVEMBER, 11), "Armistice 1918");
        days.put(LocalDate.of(year, Month.DECEMBER, 25), "Noël");
        return days.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /** Dimanche de Pâques du calendrier grégorien (algorithme de Meeus, Jones et Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
