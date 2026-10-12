package com.orpex.holiday;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, LocalDate> {

    List<Holiday> findAllByOrderByDayAsc();
}
