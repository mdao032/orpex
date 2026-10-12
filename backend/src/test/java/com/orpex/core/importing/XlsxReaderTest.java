package com.orpex.core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxReaderTest {

    @Test
    void convertsExcelDatesTimesAndNumbersToTheTextExpectedByTheValidator() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("relances");
            header(sheet, "id", "Date_Echeance", "heure_envoi", "jours_avant", "date_envoi");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(42);                                      // id numérique
            dated(workbook, row, 1, "dd/mm/yyyy").setCellValue(LocalDate.of(2026, 11, 3));
            dated(workbook, row, 2, "hh:mm").setCellValue(9.5 / 24);                  // 09:30
            row.createCell(3).setCellValue(3.0);
            row.createCell(4).setCellValue("03/04");                                  // texte ambigu, transmis tel quel

            XlsxReader.ParsedSheet parsed = read(workbook);

            assertEquals(List.of("id", "date_echeance", "heure_envoi", "jours_avant", "date_envoi"),
                    parsed.headers());
            assertEquals(1, parsed.rows().size());
            XlsxReader.SheetRow first = parsed.rows().get(0);
            assertEquals(2, first.rowNumber());
            assertEquals(Map.of("id", "42", "date_echeance", "2026-11-03", "heure_envoi", "09:30",
                    "jours_avant", "3", "date_envoi", "03/04"), first.cells());
        }
    }

    @Test
    void formulaCellsGiveTheirCachedResult() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            header(sheet, "id", "objet");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("A1");
            row.createCell(1).setCellFormula("\"Relance \"&\"facture\"");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();

            assertEquals("Relance facture", read(workbook).rows().get(0).cells().get("objet"));
        }
    }

    @Test
    void skipsEmptyRowsAndKeepsExcelRowNumbers() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            header(sheet, "id", "email");
            sheet.createRow(1).createCell(0).setCellValue("A");
            sheet.createRow(2).createCell(1).setCellValue("   ");                   // ligne blanche
            sheet.createRow(4).createCell(0).setCellValue("B");                     // ligne 4 absente

            var rows = read(workbook).rows();

            assertEquals(List.of(2, 5), rows.stream().map(XlsxReader.SheetRow::rowNumber).toList());
            assertEquals("", rows.get(0).cells().get("email"));
        }
    }

    @Test
    void refusesDuplicateHeaders() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            header(workbook.createSheet(), "id", "email", "ID");

            var error = assertThrows(XlsxReader.UnreadableFileException.class, () -> read(workbook));
            assertEquals("La colonne « id » apparaît deux fois dans les en-têtes.", error.getMessage());
        }
    }

    @Test
    void refusesAnEmptySheet() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet();

            var error = assertThrows(XlsxReader.UnreadableFileException.class, () -> read(workbook));
            assertTrue(error.getMessage().startsWith("La première feuille est vide"));
        }
    }

    @Test
    void refusesTheOldXlsFormatWithAnExplanation() throws IOException {
        try (Workbook old = new HSSFWorkbook()) {
            header(old.createSheet(), "id");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            old.write(bytes);

            var error = assertThrows(XlsxReader.UnreadableFileException.class,
                    () -> XlsxReader.read(new ByteArrayInputStream(bytes.toByteArray())));
            assertTrue(error.getMessage().contains("(.xls)"));
        }
    }

    @Test
    void refusesAFileThatIsNotAnExcelWorkbook() {
        var error = assertThrows(XlsxReader.UnreadableFileException.class,
                () -> XlsxReader.read(new ByteArrayInputStream("id;email\n1;a@b.c".getBytes())));
        assertEquals("Le fichier n'est pas un classeur Excel .xlsx lisible.", error.getMessage());
    }

    private static void header(Sheet sheet, String... names) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < names.length; i++) {
            row.createCell(i).setCellValue(names[i]);
        }
    }

    private static org.apache.poi.ss.usermodel.Cell dated(Workbook workbook, Row row, int column, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(format));
        var cell = row.createCell(column);
        cell.setCellStyle(style);
        return cell;
    }

    private static XlsxReader.ParsedSheet read(Workbook workbook) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        workbook.write(bytes);
        return XlsxReader.read(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
