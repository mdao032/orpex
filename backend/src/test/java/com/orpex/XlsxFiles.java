package com.orpex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Fichiers .xlsx de test : en-têtes, puis une ligne par map (cellules en texte). */
public final class XlsxFiles {

    public static final List<String> ALL_COLUMNS = List.of("id", "email", "prenom", "nom", "reference_dossier",
            "objet", "contenu", "mode_envoi", "date_envoi", "date_echeance", "jours_avant", "date_debut", "date_fin",
            "heure_envoi", "jours_ouvres_uniquement", "validation_quotidienne", "statut");

    private XlsxFiles() {
    }

    /** Ligne valide en mode « période » ; les tests la modifient. */
    public static Map<String, String> periodRow(String id, String email, String start, String end) {
        Map<String, String> row = new HashMap<>();
        row.put("id", id);
        row.put("email", email);
        row.put("prenom", "Awa");
        row.put("objet", "Relance facture");
        row.put("contenu", "Bonjour {{prenom}}");
        row.put("mode_envoi", "periode");
        row.put("date_debut", start);
        row.put("date_fin", end);
        row.put("heure_envoi", "09:00");
        row.put("jours_ouvres_uniquement", "oui");
        row.put("validation_quotidienne", "non");
        return row;
    }

    @SafeVarargs
    public static byte[] xlsx(List<String> headers, Map<String, String>... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("relances");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < headers.size(); c++) {
                    String value = rows[r].get(headers.get(c));
                    if (value != null) {
                        row.createCell(c).setCellValue(value);
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
