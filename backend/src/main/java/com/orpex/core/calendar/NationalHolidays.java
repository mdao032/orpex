package com.orpex.core.calendar;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Jours fériés légaux d'un pays pour une année, proposés à l'administrateur qui reste libre de les modifier.
 * Seuls les jours calculables avec certitude sont produits ; les fêtes dont la date est fixée chaque année par
 * annonce officielle (calendrier lunaire) sont listées dans {@link Country#manualDays()} pour une saisie à la main.
 */
public final class NationalHolidays {

    public enum Country {
        /** Code du travail, art. L3133-1 : 11 jours, hors Alsace-Moselle (Vendredi saint et Saint-Étienne en plus). */
        FRANCE("France métropolitaine", List.of()),
        /**
         * Loi n° 05-040 du 22 juillet 2005 : 12 jours, dont 4 fêtes musulmanes dont la date est fixée chaque année
         * par les autorités.
         */
        MALI("Mali", List.of("Korité (fin du Ramadan)", "Tabaski", "Maouloud", "Baptême du Prophète"));

        private final String label;
        private final List<String> manualDays;

        Country(String label, List<String> manualDays) {
            this.label = label;
            this.manualDays = manualDays;
        }

        public String label() {
            return label;
        }

        /** Fêtes légales non calculées, à saisir à la main une fois leur date annoncée. */
        public List<String> manualDays() {
            return manualDays;
        }
    }

    private NationalHolidays() {
    }

    /** Jours fériés calculables de l'année, par date croissante, avec leur libellé. */
    public static Map<LocalDate, String> of(Country country, int year) {
        Map<LocalDate, String> days = new TreeMap<>();
        LocalDate easter = easterSunday(year);
        switch (country) {
            case FRANCE -> {
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
            }
            case MALI -> {
                days.put(LocalDate.of(year, Month.JANUARY, 1), "Jour de l'an");
                days.put(LocalDate.of(year, Month.JANUARY, 20), "Fête de l'Armée");
                days.put(LocalDate.of(year, Month.MARCH, 26), "Journée des Martyrs");
                days.put(easter.plusDays(1), "Lundi de Pâques");
                days.put(LocalDate.of(year, Month.MAY, 1), "Fête du Travail");
                days.put(LocalDate.of(year, Month.MAY, 25), "Fête de l'Afrique");
                days.put(LocalDate.of(year, Month.SEPTEMBER, 22), "Fête de l'Indépendance");
                days.put(LocalDate.of(year, Month.DECEMBER, 25), "Noël");
            }
        }
        return days;
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
