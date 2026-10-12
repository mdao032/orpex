package com.orpex.api;

import com.orpex.core.calendar.NationalHolidays;
import com.orpex.holiday.Holiday;
import com.orpex.holiday.HolidayService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Jours fériés ; réservé au rôle ADMIN par la configuration de sécurité ({@code /api/admin/**}). */
@RestController
@RequestMapping("/api/admin/holidays")
public class AdminHolidayController {

    public record HolidayView(LocalDate day, String label) {

        static HolidayView of(Holiday holiday) {
            return new HolidayView(holiday.getDay(), holiday.getLabel());
        }
    }

    /** {@code manualDays} : fêtes légales non calculées, à saisir à la main une fois leur date annoncée. */
    public record YearAddedView(List<HolidayView> added, int alreadyPresent, List<String> manualDays) {
    }

    public record CountryView(NationalHolidays.Country code, String label, List<String> manualDays) {
    }

    public record AddHolidayRequest(@NotNull LocalDate day, @NotBlank @Size(max = 200) String label) {
    }

    private final HolidayService holidayService;

    public AdminHolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping
    public List<HolidayView> list() {
        return holidayService.list().stream().map(HolidayView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayView add(@Valid @RequestBody AddHolidayRequest request) {
        return HolidayView.of(holidayService.add(request.day(), request.label()));
    }

    /** Pays dont les jours fériés peuvent être ajoutés automatiquement. */
    @GetMapping("/countries")
    public List<CountryView> countries() {
        return Arrays.stream(NationalHolidays.Country.values())
                .map(country -> new CountryView(country, country.label(), country.manualDays()))
                .toList();
    }

    /** Ajoute les jours fériés calculables du pays pour l'année ; les dates déjà présentes sont ignorées. */
    @PostMapping("/national/{country}/{year}")
    public YearAddedView addNationalHolidays(@PathVariable NationalHolidays.Country country, @PathVariable int year) {
        HolidayService.YearAdded result = holidayService.addNationalHolidays(country, year);
        return new YearAddedView(result.added().stream().map(HolidayView::of).toList(), result.alreadyPresent(),
                country.manualDays());
    }

    @DeleteMapping("/{day}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        holidayService.remove(day);
    }
}
