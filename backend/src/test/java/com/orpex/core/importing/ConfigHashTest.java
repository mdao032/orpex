package com.orpex.core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigHashTest {

    private static Map<String, String> row() {
        Map<String, String> row = new HashMap<>();
        row.put("email", "a@exemple.fr");
        row.put("objet", "Rappel");
        row.put("contenu", "Bonjour");
        row.put("mode_envoi", "periode");
        row.put("validation_quotidienne", "oui");
        return row;
    }

    @Test
    void sameConfiguration_givesTheSameHash_evenWithSpacesAndCase() {
        var other = row();
        other.put("objet", "  Rappel ");
        other.put("validation_quotidienne", "OUI");
        assertEquals(ConfigHash.of(row()), ConfigHash.of(other));
    }

    @Test
    void changedBody_givesAnotherHash() {
        var other = row();
        other.put("contenu", "Bonsoir");
        assertFalse(ConfigHash.of(row()).equals(ConfigHash.of(other)));
    }

    @Test
    void identifier_isNotPartOfTheHash() {
        var other = row();
        other.put("id", "AUTRE");
        assertEquals(ConfigHash.of(row()), ConfigHash.of(other));
    }
}
