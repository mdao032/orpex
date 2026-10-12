package com.orpex.holiday;

import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.FrenchHolidays;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Jours fériés configurés par l'administrateur, et calendrier des jours ouvrés qui en découle pour le cœur métier.
 * Les messages des exceptions sont affichés tels quels à l'utilisateur.
 */
@Service
public class HolidayService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HolidayRepository holidays;

    public HolidayService(HolidayRepository holidays) {
        this.holidays = holidays;
    }

    /**
     * Calendrier à jour au moment de l'appel : le relire à chaque calcul d'envois, pour qu'un jour férié ajouté par
     * l'administrateur soit pris en compte par les calculs suivants (quelques dizaines de lignes au plus).
     */
    @Transactional(readOnly = true)
    public BusinessCalendar businessCalendar() {
        return new BusinessCalendar(holidays.findAll().stream().map(Holiday::getDay).toList());
    }

    @Transactional(readOnly = true)
    public List<Holiday> list() {
        return holidays.findAllByOrderByDayAsc();
    }

    @Transactional
    public Holiday add(LocalDate day, String label) {
        if (holidays.existsById(day)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le " + DISPLAY.format(day) + " est déjà un jour férié.");
        }
        return holidays.save(new Holiday(day, label));
    }

    @Transactional
    public void remove(LocalDate day) {
        if (!holidays.existsById(day)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Le " + DISPLAY.format(day) + " n'est pas un jour férié.");
        }
        holidays.deleteById(day);
    }

    /** Résultat de l'ajout des jours fériés d'une année : ceux ajoutés, et le nombre de dates déjà présentes. */
    public record YearAdded(List<Holiday> added, int alreadyPresent) {
    }

    /**
     * Ajoute les jours fériés français de l'année. Une date déjà présente est laissée telle quelle (libellé compris) :
     * l'administrateur peut avoir saisi ses propres libellés.
     */
    @Transactional
    public YearAdded addFrenchHolidays(int year) {
        if (year < 2000 || year > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choisissez une année entre 2000 et 2100.");
        }
        List<Holiday> added = new ArrayList<>();
        int alreadyPresent = 0;
        for (var entry : FrenchHolidays.of(year).entrySet()) {
            if (holidays.existsById(entry.getKey())) {
                alreadyPresent++;
            } else {
                added.add(holidays.save(new Holiday(entry.getKey(), entry.getValue())));
            }
        }
        return new YearAdded(added, alreadyPresent);
    }
}
