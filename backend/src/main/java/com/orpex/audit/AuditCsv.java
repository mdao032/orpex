package com.orpex.audit;

import com.orpex.audit.AuditQueryService.AuditView;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Export CSV du journal pour Excel en français : UTF-8 avec BOM, séparateur « ; », heures dans le fuseau de
 * l'installation. Une valeur qui commencerait par = + - @ est préfixée d'une apostrophe, pour qu'Excel ne l'exécute
 * jamais comme une formule.
 */
public final class AuditCsv {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String HEADER = "Date;Auteur;Événement;Objet;Identifiant;Avant;Après";

    private AuditCsv() {
    }

    public static byte[] write(List<AuditView> views, ZoneId zone) {
        StringBuilder csv = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (AuditView v : views) {
            csv.append(cell(TIME.format(v.occurredAt().atZone(zone)))).append(';')
                    .append(cell(v.actor() == null ? "système" : v.actor())).append(';')
                    .append(cell(v.eventLabel())).append(';')
                    .append(cell(v.objectType())).append(';')
                    .append(cell(v.objectId())).append(';')
                    .append(cell(v.before())).append(';')
                    .append(cell(v.after())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(";") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
