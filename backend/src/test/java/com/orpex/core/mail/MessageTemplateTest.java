package com.orpex.core.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTemplateTest {

    @Test
    void replacesEveryVariableEvenWithSpacesInsideTheBraces() {
        String rendered = MessageTemplate.render("Bonjour {{prenom}}, facture {{ reference_dossier }} : {{montant}} FCFA",
                Map.of("prenom", "Awa", "reference_dossier", "FAC-1", "montant", "125 000"));

        assertEquals("Bonjour Awa, facture FAC-1 : 125 000 FCFA", rendered);
    }

    @Test
    void valuesAreInsertedLiterally() {
        assertEquals("Total : $1 \\ fin", MessageTemplate.render("Total : {{montant}} fin",
                Map.of("montant", "$1 \\")));
    }

    @Test
    void aMissingOrBlankVariableNeverLeavesEmpty() {
        var missing = assertThrows(MessageTemplate.MissingVariableException.class,
                () -> MessageTemplate.render("Bonjour {{prenom}}", Map.of()));
        assertEquals("la variable {{prenom}} n'a pas de valeur", missing.getMessage());
        assertThrows(MessageTemplate.MissingVariableException.class,
                () -> MessageTemplate.render("Bonjour {{prenom}}", Map.of("prenom", "  ")));
    }

    @Test
    void keepsOnlyTheValuesOfTheVariablesUsed() {
        Map<String, String> cells = Map.of("prenom", " Awa ", "nom", "Traoré", "montant", "125 000", "vide", "");

        Map<String, String> used = MessageTemplate.valuesUsed(cells, "Bonjour {{prenom}} {{vide}}", "Montant {{montant}}");

        assertEquals(Map.of("prenom", "Awa", "montant", "125 000"), used);
        assertEquals(List.of("prenom", "vide", "montant"),
                List.copyOf(MessageTemplate.variables("Bonjour {{prenom}} {{vide}}", "Montant {{montant}} {{prenom}}")));
    }
}
