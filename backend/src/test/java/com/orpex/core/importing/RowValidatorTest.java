package com.orpex.core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.SendMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowValidatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-12");

    private final RowValidator validator = new RowValidator(BusinessCalendar.weekendsOnly());

    /** Une ligne valide en mode « avant échéance » ; les tests la modifient pour provoquer chaque erreur. */
    private static Map<String, String> validRow() {
        Map<String, String> row = new HashMap<>();
        row.put("id", "DOS-001");
        row.put("email", "prenom.nom@exemple.fr");
        row.put("prenom", "Claire");
        row.put("nom", "Martin");
        row.put("objet", "Rappel dossier {{reference_dossier}}");
        row.put("contenu", "Bonjour {{prenom}}, votre dossier arrive à échéance.");
        row.put("reference_dossier", "REF-42");
        row.put("mode_envoi", "avant_echeance");
        row.put("date_echeance", "2026-10-30");
        row.put("jours_avant", "3");
        row.put("heure_envoi", "09:00");
        row.put("jours_ouvres_uniquement", "oui");
        row.put("validation_quotidienne", "non");
        return row;
    }

    @Test
    void validRow_producesATypedRule() {
        var result = validator.validate(validRow(), TODAY);
        assertTrue(result.isValid(), result.errors().toString());
        assertEquals("DOS-001", result.rule().businessId());
        assertEquals(SendMode.AVANT_ECHEANCE, result.rule().scheduling().mode());
        assertEquals(LocalTime.of(9, 0), result.rule().sendTime());
        assertFalse(result.rule().dailyValidation());
        assertEquals(RuleStatus.ACTIVE, result.rule().status());
    }

    @Test
    void emptyDailyValidation_isRejected() {
        var row = validRow();
        row.put("validation_quotidienne", "");
        var result = validator.validate(row, TODAY);
        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).startsWith("validation_quotidienne"));
    }

    @Test
    void dailyValidation_acceptsOnlyOuiOrNon() {
        var row = validRow();
        row.put("validation_quotidienne", "peut-être");
        assertFalse(validator.validate(row, TODAY).isValid());
    }

    @Test
    void invalidEmail_isRejected() {
        var row = validRow();
        row.put("email", "pas-un-email");
        assertFalse(validator.validate(row, TODAY).isValid());
    }

    @Test
    void variableWithoutMatchingColumn_isRejected() {
        var row = validRow();
        row.put("contenu", "Bonjour {{montant}}");
        var result = validator.validate(row, TODAY);
        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("{{montant}}"));
    }

    @Test
    void periodEndingBeforeItsStart_isRejected() {
        var row = validRow();
        row.put("mode_envoi", "periode");
        row.put("date_debut", "2026-11-10");
        row.put("date_fin", "2026-11-05");
        assertFalse(validator.validate(row, TODAY).isValid());
    }

    @Test
    void beforeDueWithoutDaysBefore_isRejected() {
        var row = validRow();
        row.remove("jours_avant");
        assertFalse(validator.validate(row, TODAY).isValid());
    }

    @Test
    void ambiguousDate_isRejected() {
        var row = validRow();
        row.put("date_echeance", "30/10/2026");
        assertFalse(validator.validate(row, TODAY).isValid());
    }

    @Test
    void dueOnWeekend_withBusinessDaysOnly_givesAWarningButIsValid() {
        var row = validRow();
        row.put("date_echeance", "2026-10-31");
        var result = validator.validate(row, TODAY);
        assertTrue(result.isValid());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void validatorColumn_isIgnoredWithAWarning() {
        var row = validRow();
        row.put("validateur", "chef@exemple.fr");
        var result = validator.validate(row, TODAY);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("validateur")));
    }

    @Test
    void missingRequiredColumns_areReported() {
        List<String> headers = List.of("id", "email", "objet", "contenu");
        assertEquals(
                List.of("mode_envoi", "heure_envoi", "jours_ouvres_uniquement", "validation_quotidienne"),
                RowValidator.missingColumns(headers));
    }
}
