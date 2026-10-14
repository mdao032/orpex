package com.orpex.core.mail;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variables des messages : {@code {{nom_de_colonne}}} (spécification §4), remplacées par la valeur de la colonne de
 * la ligne. Une variable sans valeur ne doit jamais partir vide : le rendu échoue.
 */
public final class MessageTemplate {

    /** Même syntaxe partout (validation à l'import, enregistrement des valeurs, rendu à l'envoi). */
    public static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*}}");

    /** Une variable du message n'a pas de valeur : le mail ne doit pas partir. */
    public static final class MissingVariableException extends RuntimeException {

        public MissingVariableException(String name) {
            super("la variable {{" + name + "}} n'a pas de valeur");
        }
    }

    private MessageTemplate() {
    }

    /** Noms des variables utilisées dans les textes, dans l'ordre d'apparition, sans doublon. */
    public static Set<String> variables(String... texts) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = VARIABLE.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** Valeurs des variables utilisées dans les textes, prises dans les cellules de la ligne (clés en minuscules). */
    public static Map<String, String> valuesUsed(Map<String, String> cells, String... texts) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : variables(texts)) {
            String value = cells.get(name);
            if (value != null && !value.isBlank()) {
                values.put(name, value.strip());
            }
        }
        return values;
    }

    /** Remplace chaque variable par sa valeur ; une variable absente ou vide fait échouer le rendu. */
    public static String render(String text, Map<String, String> values) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null || value.isBlank()) {
                throw new MissingVariableException(matcher.group(1));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
