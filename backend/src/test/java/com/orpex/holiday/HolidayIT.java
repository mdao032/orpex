package com.orpex.holiday;

import static org.assertj.core.api.Assertions.assertThat;

import com.orpex.TestcontainersConfiguration;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.SchedulingRule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@Import(TestcontainersConfiguration.class)
class HolidayIT {

    @Autowired
    HolidayRepository holidays;

    @Autowired
    HolidayService holidayService;

    @Test
    void aHolidayStoredInTheDatabaseIsSkippedBySendCalculation() {
        var rule = SchedulingRule.period(LocalDate.parse("2026-12-23"), LocalDate.parse("2026-12-28"), true);
        assertThat(new ScheduleCalculator(holidayService.businessCalendar()).sendDates(rule))
                .contains(LocalDate.parse("2026-12-25"));

        holidays.save(new Holiday(LocalDate.parse("2026-12-25"), " Noël "));

        assertThat(new ScheduleCalculator(holidayService.businessCalendar()).sendDates(rule))
                .containsExactly(LocalDate.parse("2026-12-23"), LocalDate.parse("2026-12-24"),
                        LocalDate.parse("2026-12-28"));
        assertThat(holidays.findById(LocalDate.parse("2026-12-25")).orElseThrow().getLabel()).isEqualTo("Noël");
    }
}
