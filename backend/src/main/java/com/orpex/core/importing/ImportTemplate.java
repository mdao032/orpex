package com.orpex.core.importing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Modèle de fichier d'import (spécification §4) : feuille « relances » (lue par l'import) avec listes déroulantes et
 * lignes d'exemple, feuille « instructions » décrivant chaque colonne.
 *
 * <p>Les exemples ont des dates futures, calculées au téléchargement, et des adresses {@code @example.org} : ce
 * domaine est réservé (RFC 2606), aucun mail ne peut y arriver si les exemples ne sont pas supprimés.
 */
public final class ImportTemplate {

    /** Lignes équipées des listes déroulantes et des formats. */
    static final int PREPARED_ROWS = 1000;

    private record Column(String name, boolean required, String description, Kind kind, int width) {
    }

    private enum Kind { TEXT, DATE, TIME, NUMBER }

    private static final List<Column> COLUMNS = List.of(
            new Column(Columns.ID, true, "Identifiant stable et unique de la ligne ; sert de clé au ré-import. "
                    + "Ne jamais le changer pour une relance existante.", Kind.TEXT, 14),
            new Column(Columns.EMAIL, true, "Adresse du destinataire.", Kind.TEXT, 30),
            new Column(Columns.FIRST_NAME, false, "Prénom, utilisable dans le message : {{prenom}}.", Kind.TEXT, 14),
            new Column(Columns.LAST_NAME, false, "Nom, utilisable dans le message : {{nom}}.", Kind.TEXT, 14),
            new Column(Columns.CASE_REFERENCE, false, "Référence affichée dans la validation et le journal ; "
                    + "utilisable : {{reference_dossier}}.", Kind.TEXT, 18),
            new Column(Columns.SUBJECT, true, "Objet du mail ; variables autorisées.", Kind.TEXT, 34),
            new Column(Columns.BODY, true, "Corps du mail ; variables autorisées.", Kind.TEXT, 50),
            new Column(Columns.SEND_MODE, true, "ponctuel, avant_echeance ou periode (liste déroulante).", Kind.TEXT, 16),
            new Column(Columns.SEND_DATE, false, "Si ponctuel : date de l'envoi unique.", Kind.DATE, 13),
            new Column(Columns.DUE_DATE, false, "Si avant_echeance : date d'échéance, incluse.", Kind.DATE, 13),
            new Column(Columns.DAYS_BEFORE, false, "Si avant_echeance : nombre de jours avant l'échéance où "
                    + "commence l'envoi quotidien (0 ou plus).", Kind.NUMBER, 11),
            new Column(Columns.START_DATE, false, "Si periode : premier jour d'envoi, inclus.", Kind.DATE, 13),
            new Column(Columns.END_DATE, false, "Si periode : dernier jour d'envoi, inclus.", Kind.DATE, 13),
            new Column(Columns.SEND_TIME, true, "Heure d'envoi HH:MM, dans le fuseau de l'installation.", Kind.TIME, 11),
            new Column(Columns.BUSINESS_DAYS_ONLY, true, "oui : saute les week-ends et jours fériés. Jamais vide.",
                    Kind.TEXT, 13),
            new Column(Columns.DAILY_VALIDATION, true, "oui : chaque envoi attend votre validation du jour ; "
                    + "sans validation, rien ne part. Jamais vide.", Kind.TEXT, 13),
            new Column(Columns.STATUS, false, "actif (par défaut) ou clos ; clos annule les envois futurs.",
                    Kind.TEXT, 9));

    private ImportTemplate() {
    }

    public static byte[] create(LocalDate today) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            createRulesSheet(workbook.createSheet("relances"), styles, today);
            createInstructionsSheet(workbook.createSheet("instructions"), styles);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createRulesSheet(Sheet sheet, Styles styles, LocalDate today) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < COLUMNS.size(); c++) {
            Column column = COLUMNS.get(c);
            Cell cell = header.createCell(c);
            cell.setCellValue(column.name());
            cell.setCellStyle(column.required() ? styles.requiredHeader : styles.header);
            sheet.setColumnWidth(c, column.width() * 256);
            sheet.setDefaultColumnStyle(c, styles.forKind(column.kind()));
        }
        sheet.createFreezePane(0, 1);

        dropDown(sheet, Columns.SEND_MODE, "ponctuel", "avant_echeance", "periode");
        dropDown(sheet, Columns.BUSINESS_DAYS_ONLY, "oui", "non");
        dropDown(sheet, Columns.DAILY_VALIDATION, "oui", "non");
        dropDown(sheet, Columns.STATUS, "actif", "clos");

        LocalDate monday = today.plusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Map<String, Object>> examples = List.of(
                Map.of(Columns.ID, "EXEMPLE-1", Columns.EMAIL, "awa.traore@example.org", Columns.FIRST_NAME, "Awa",
                        Columns.CASE_REFERENCE, "FAC-2026-001", Columns.SUBJECT, "Facture {{reference_dossier}}",
                        Columns.BODY, "Bonjour {{prenom}}, merci de nous transmettre la facture {{reference_dossier}}.",
                        Columns.SEND_MODE, "ponctuel", Columns.SEND_DATE, monday, Columns.SEND_TIME, "09:00"),
                Map.of(Columns.ID, "EXEMPLE-2", Columns.EMAIL, "service.comptable@example.org",
                        Columns.SUBJECT, "Rappel : échéance du rapport mensuel",
                        Columns.BODY, "Bonjour, le rapport mensuel est attendu à l'échéance. Merci.",
                        Columns.SEND_MODE, "avant_echeance", Columns.DUE_DATE, monday.plusDays(4),
                        Columns.DAYS_BEFORE, 3, Columns.SEND_TIME, "08:30"),
                Map.of(Columns.ID, "EXEMPLE-3", Columns.EMAIL, "moussa.keita@example.org", Columns.FIRST_NAME, "Moussa",
                        Columns.SUBJECT, "Pièces manquantes",
                        Columns.BODY, "Bonjour {{prenom}}, il manque encore des pièces à votre dossier.",
                        Columns.SEND_MODE, "periode", Columns.START_DATE, monday.plusWeeks(1),
                        Columns.END_DATE, monday.plusWeeks(1).plusDays(4), Columns.SEND_TIME, "10:00"));
        for (int r = 0; r < examples.size(); r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Column column = COLUMNS.get(c);
                Object value = examples.get(r).get(column.name());
                if (column.name().equals(Columns.BUSINESS_DAYS_ONLY)) {
                    value = "oui";
                } else if (column.name().equals(Columns.DAILY_VALIDATION)) {
                    value = r == 0 ? "oui" : "non";
                } else if (column.name().equals(Columns.STATUS)) {
                    value = "actif";
                }
                write(row.createCell(c), value, column.kind(), styles);
            }
        }
    }

    private static void write(Cell cell, Object value, Kind kind, Styles styles) {
        cell.setCellStyle(styles.forKind(kind));
        switch (value) {
            case null -> { }
            case LocalDate date -> cell.setCellValue(date);
            case Integer number -> cell.setCellValue(number);
            case String text when kind == Kind.TIME -> {
                String[] parts = text.split(":");
                cell.setCellValue((Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])) / 1440.0);
            }
            default -> cell.setCellValue(value.toString());
        }
    }

    private static void dropDown(Sheet sheet, String columnName, String... values) {
        int column = COLUMNS.stream().map(Column::name).toList().indexOf(columnName);
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidation validation = helper.createValidation(helper.createExplicitListConstraint(values),
                new CellRangeAddressList(1, PREPARED_ROWS, column, column));
        validation.setShowErrorBox(true);
        validation.createErrorBox("Valeur non prévue", "Choisissez une valeur dans la liste : " + String.join(", ", values));
        sheet.addValidationData(validation);
    }

    private static void createInstructionsSheet(Sheet sheet, Styles styles) {
        String[] intro = {
                "Mode d'emploi du fichier d'import ORPEX",
                "Une ligne de la feuille « relances » = un destinataire, un message et un calendrier d'envoi.",
                "Supprimez les lignes EXEMPLE avant d'importer vos propres lignes.",
                "Dates : saisissez une vraie date (format AAAA-MM-JJ). Un texte comme 03/04 est refusé : il est ambigu.",
                "Variables : {{prenom}}, {{nom}}, {{reference_dossier}}… Une variable vide fait refuser la ligne : "
                        + "aucun mail ne part avec un champ non rempli.",
                "Après l'import, un aperçu montre chaque ligne telle qu'elle a été comprise ; rien n'est appliqué "
                        + "avant votre confirmation.",
        };
        int r = 0;
        for (String line : intro) {
            Cell cell = sheet.createRow(r++).createCell(0);
            cell.setCellValue(line);
            cell.setCellStyle(r == 1 ? styles.title : styles.text);
        }
        r++;
        Row header = sheet.createRow(r++);
        String[] titles = {"Colonne", "Obligatoire", "Description"};
        for (int c = 0; c < titles.length; c++) {
            header.createCell(c).setCellValue(titles[c]);
            header.getCell(c).setCellStyle(styles.header);
        }
        for (Column column : COLUMNS) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(column.name());
            row.createCell(1).setCellValue(column.required() ? "oui" : "selon le mode");
            row.createCell(2).setCellValue(column.description());
        }
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 100 * 256);
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle requiredHeader;
        final CellStyle title;
        final CellStyle text;
        final CellStyle date;
        final CellStyle time;
        final CellStyle number;

        Styles(XSSFWorkbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setBorderBottom(BorderStyle.THIN);
            requiredHeader = workbook.createCellStyle();
            requiredHeader.cloneStyleFrom(header);
            Font boldUnderlined = workbook.createFont();
            boldUnderlined.setBold(true);
            boldUnderlined.setUnderline(Font.U_SINGLE);
            requiredHeader.setFont(boldUnderlined);
            Font big = workbook.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(big);
            var format = workbook.getCreationHelper().createDataFormat();
            text = workbook.createCellStyle();
            text.setDataFormat(format.getFormat("@"));
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("yyyy-mm-dd"));
            time = workbook.createCellStyle();
            time.setDataFormat(format.getFormat("hh:mm"));
            number = workbook.createCellStyle();
            number.setDataFormat(format.getFormat("0"));
        }

        CellStyle forKind(Kind kind) {
            return switch (kind) {
                case TEXT -> text;
                case DATE -> date;
                case TIME -> time;
                case NUMBER -> number;
            };
        }
    }
}
