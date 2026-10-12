package com.orpex.holiday;

import com.orpex.core.calendar.BusinessCalendar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fournit au cœur métier le calendrier des jours ouvrés, à partir des jours fériés en base. */
@Service
public class HolidayService {

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
}
