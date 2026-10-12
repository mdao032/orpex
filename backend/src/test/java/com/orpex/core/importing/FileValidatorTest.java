package com.orpex.core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orpex.core.calendar.BusinessCalendar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileValidatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-12");
    private static final List<String> HEADERS = List.of("id", "email", "objet", "contenu", "mode_envoi",
            "date_envoi", "date_debut", "date_fin", "heure_envoi", "jours_ouvres_uniquement", "validation_quotidienne",
            "statut");

    private final FileValidator validator = new FileValidator(BusinessCalendar.weekendsOnly());

    /** Envoi quotidien du 19 au 23 octobre 2026 (du lundi au vendredi). */
    private static Map<String, String> period(String id, String email, String start, String end) {
        Map<String, String> cells = new HashMap<>();
        cells.put("id", id);
        cells.put("email", email);
        cells.put("objet", "Relance");
        cells.put("contenu", "Bonjour");
        cells.put("mode_envoi", "periode");
        cells.put("date_debut", start);
        cells.put("date_fin", end);
        cells.put("heure_envoi", "09:00");
        cells.put("jours_ouvres_uniquement", "oui");
        cells.put("validation_quotidienne", "non");
        return cells;
    }

    @SafeVarargs
    private static XlsxReader.ParsedSheet sheet(Map<String, String>... rows) {
        List<XlsxReader.SheetRow> sheetRows = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            sheetRows.add(new XlsxReader.SheetRow(i + 2, rows[i]));
        }
        return new XlsxReader.ParsedSheet(HEADERS, sheetRows);
    }

    @Test
    void validRowsGiveTypedRules() {
        var result = validator.validate(sheet(period("A", "a@exemple.ml", "2026-10-19", "2026-10-23")), TODAY);

        assertFalse(result.isRejected());
        assertEquals(1, result.validCount());
        assertNotNull(result.rows().get(0).rule());
        assertEquals(2, result.rows().get(0).rowNumber());
    }

    @Test
    void missingRequiredColumnsRejectTheWholeFile() {
        var sheet = new XlsxReader.ParsedSheet(List.of("id", "email"), List.of());

        var result = validator.validate(sheet, TODAY);

        assertTrue(result.isRejected());
        assertTrue(result.missingColumns().contains("validation_quotidienne"));
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void anIdPresentTwiceRejectsBothRows() {
        var result = validator.validate(sheet(
                period("A", "a@exemple.ml", "2026-10-19", "2026-10-23"),
                period("B", "b@exemple.ml", "2026-10-19", "2026-10-23"),
                period("A", "c@exemple.ml", "2026-10-19", "2026-10-23")), TODAY);

        assertEquals(1, result.validCount());
        assertEquals(List.of("id : « A » présent plusieurs fois dans le fichier (lignes 2, 4)"),
                result.rows().get(0).errors());
        assertNull(result.rows().get(2).rule());
    }

    @Test
    void aRowWhoseDatesAreAllPastIsRejected() {
        Map<String, String> once = period("P", "p@exemple.ml", "", "");
        once.put("mode_envoi", "ponctuel");
        once.put("date_envoi", "2026-10-01");

        var result = validator.validate(sheet(once, period("Q", "q@exemple.ml", "2026-09-01", "2026-09-30")), TODAY);

        assertEquals(0, result.validCount());
        result.rows().forEach(row -> assertEquals(
                List.of("calendrier : aucun envoi à venir, toutes les dates calculées sont passées"), row.errors()));
    }

    @Test
    void aClosedRowNeedsNoUpcomingSend() {
        Map<String, String> closed = period("C", "c@exemple.ml", "2026-09-01", "2026-09-30");
        closed.put("statut", "clos");

        assertEquals(1, validator.validate(sheet(closed), TODAY).validCount());
    }

    @Test
    void sameRecipientWithSendsOnTheSameDayGivesAWarningOnBothRows() {
        var result = validator.validate(sheet(
                period("A", "Destinataire@exemple.ml", "2026-10-19", "2026-10-21"),
                period("B", "destinataire@exemple.ml", "2026-10-21", "2026-10-23"),
                period("C", "destinataire@exemple.ml", "2026-10-26", "2026-10-30")), TODAY);

        assertEquals(3, result.validCount());
        assertEquals(List.of("email : même destinataire que la ligne 3, avec des envois les mêmes jours "
                + "(risque de doublon pour le destinataire)"), result.rows().get(0).warnings());
        assertTrue(result.rows().get(1).warnings().get(0).contains("la ligne 2"));
        assertTrue(result.rows().get(2).warnings().isEmpty(), "pas de jour commun avec les autres lignes");
    }
}
