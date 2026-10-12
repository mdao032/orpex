package com.orpex.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimeConfigTest {

    @Test
    void acceptsAnIanaTimezone() {
        assertEquals(ZoneId.of("Africa/Bamako"), TimeConfig.zoneOf(" Africa/Bamako "));
    }

    @Test
    void refusesAnInvalidOrMissingTimezoneWithAClearMessage() {
        var invalid = assertThrows(IllegalStateException.class, () -> TimeConfig.zoneOf("Bamako"));
        assertTrue(invalid.getMessage().contains("orpex.timezone (ORPEX_TIMEZONE) invalide : 'Bamako'"));

        assertThrows(IllegalStateException.class, () -> TimeConfig.zoneOf(""));
        assertThrows(IllegalStateException.class, () -> TimeConfig.zoneOf(null));
    }
}
