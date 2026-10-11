package com.orpex.core.importing;

import java.util.List;

/**
 * Résultat de la validation d'une ligne : {@code rule} n'est renseignée que si {@code errors} est vide.
 * Les avertissements ne bloquent pas la ligne.
 */
public record RowValidation(List<String> errors, List<String> warnings, ImportedRule rule) {

    public boolean isValid() {
        return errors.isEmpty();
    }
}
